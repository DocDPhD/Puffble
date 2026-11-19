// Puffble — a Pebble Puffco companion.
//
// Shows live Puffco Proxy state relayed by the Android companion (which owns the BLE
// link) and sends profile / start / abort back. Buttons only, so Emery (Pebble Time 2)
// and Gabbro (Pebble Round 2) behave the same.
//
//   Idle:            UP/DOWN change profile (shown at once, error if the Proxy refuses)
//                    SELECT opens Confirm (on the Custom page: the editor first)
//                    HOLD UP opens Settings;  BACK twice exits
//   Custom editor:   UP/DOWN change temperature, SELECT; then time, SELECT; BACK goes back
//   Confirm:         HOLD SELECT (or press it twice, per Settings) starts heating
//   Cooling off:     HOLD SELECT skips the suggested wait
//   Session:         SELECT twice boosts (+15 s, hotter)
//   Preheat/Session: HOLD SELECT stops (holding BACK exits the app on the Round 2)
//
// Message contract: WatchProtocol.kt in the companion and "messageKeys" in package.json.

#include <pebble.h>

#define PROFILE_COUNT 4
#define NAME_LEN 24
#define TEXT_LEN 48
#define DETAIL_LEN 96  // status text from the phone can run to three lines

#define STATE_IDLE 5
#define STATE_PREHEAT 7
#define STATE_ACTIVE 8
#define STATE_FADE 9
#define STATE_COOLDOWN 254  // phone-made: the Proxy shut itself off to cool
#define STATE_SEARCHING 255

#define CMD_START 1
#define CMD_ABORT 2
#define CMD_REFRESH 3
#define CMD_SKIP_COOLDOWN 4
#define CMD_BOOST 5
#define CMD_START_CUSTOM 6

#define REFRESH_RETRY_MS 3000
#define ECHO_TIMEOUT_MS 4000
#define ERROR_SHOW_MS 3000
#define TIP_SHOW_MS 4000
#define LONG_PRESS_MS 700
#define FRAME_MS 40      // transitions
#define FLICKER_MS 180   // flame flicker / searching dots

// Settings bits. Stored as "off" flags so the default (0) is everything on.
#define SET_CELSIUS 1
#define SET_NO_VIBES 2
#define SET_NO_ANIM 4
#define SET_NO_PINS 8
#define SET_NO_TINT 16
#define SET_NO_SESSION_ALERTS 32
#define SET_LIGHT_ON 64  // opt-in, so stored as an "on" flag
#define SET_START_DOUBLE 128  // press SELECT twice to start, instead of holding it
#define PERSIST_SETTINGS 1
#define PERSIST_CUSTOM_F 2
#define PERSIST_CUSTOM_S 3

// The one-time custom page comes after the four stored profiles.
#define CUSTOM_PAGE PROFILE_COUNT
#define PAGE_COUNT (PROFILE_COUNT + 1)
#define CUSTOM_RGB 0xFF5500
#define CUSTOM_F_MIN 400
#define CUSTOM_F_MAX 620
#define CUSTOM_S_MIN 15
#define CUSTOM_S_MAX 180
#define DOUBLE_PRESS_MS 450
#define BOOST_TIP_MS 5000

#define DEG "\xc2\xb0"
#define DOT " \xc2\xb7 "
#define PX_DEG "\001"  // degree sign in the pixel font

#define ABS(x) ((x) < 0 ? -(x) : (x))

static int prv_isqrt(int n) {
  if (n <= 0) return 0;
  int x = n, y = (x + 1) / 2;
  while (y < x) {
    x = y;
    y = (x + n / x) / 2;
  }
  return x;
}

// ---- state from the phone -----------------------------------------------------

static bool s_have_msg;
static uint8_t s_state = STATE_SEARCHING;
static uint8_t s_profile, s_batt;
static uint16_t s_target_f, s_duration_s, s_heater_f;
static int16_t s_remain_s = -1;
static uint64_t s_remain_at_ms;
static uint32_t s_ring_rgb = 0x303030;
static char s_name[NAME_LEN];
static char s_status[DETAIL_LEN];

static bool s_have_table;
static char s_p_name[PROFILE_COUNT][NAME_LEN];
static uint16_t s_p_target_f[PROFILE_COUNT], s_p_duration_s[PROFILE_COUNT];
static uint32_t s_p_rgb[PROFILE_COUNT];

// ---- local state ----------------------------------------------------------------

typedef enum { OUT_NONE, OUT_PROFILE, OUT_START, OUT_ABORT, OUT_REFRESH, OUT_SETTINGS, OUT_BOOST, OUT_CUSTOM } OutKind;

static OutKind s_out_kind = OUT_NONE;  // message currently in the outbox
static int s_pending_cmd;              // START/ABORT/REFRESH waiting for the outbox
static bool s_settings_dirty;          // settings waiting for the outbox
static int s_want_profile = -1;        // optimistic selection, -1 = none
static bool s_profile_dirty;           // selection changed while a send was in flight

static uint8_t s_settings;
static bool s_custom_page;              // the custom page is showing (idle)
static bool s_confirm_custom;           // the confirm screen is for a custom session
static int s_custom_f = 500, s_custom_s = 60;
static int s_edit_field;                // custom editor: 0 temperature, 1 time
static uint64_t s_last_select_ms;       // for double-press detection
static uint64_t s_active_at_ms;         // when the session reached temperature
static char s_error[TEXT_LEN];
static bool s_show_tip = true;         // "Hold UP: settings" right after launch

// Animation: displayed values ease toward their targets.
static int32_t s_arc_shown = -1;       // permille, -1 = hidden
static int32_t s_temp_shown;           // displayed heater °F during preheat
static int s_slide_from = -1;          // page sliding out, -1 = no slide
static int s_slide_dir;                // +1 new page comes up from below, -1 from above
static int32_t s_slide_p;              // slide progress, permille
static int32_t s_fade_level = 1000;    // done-ring fade, permille
static uint32_t s_frame;               // flicker counter
static uint8_t s_anim_state = STATE_SEARCHING;

static Window *s_main_window, *s_confirm_window, *s_settings_window, *s_edit_window;
static Layer *s_main_canvas, *s_confirm_canvas, *s_edit_canvas;
static MenuLayer *s_menu;
static AppTimer *s_refresh_timer, *s_echo_timer, *s_error_timer, *s_anim_timer, *s_tip_timer;

// ---- pixel font (5x7, chunky) ----------------------------------------------------

typedef struct {
  char c;
  uint8_t w;
  uint8_t rows[7];
} Glyph;

static const Glyph GLYPHS[] = {
    {'0', 5, {0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E}}, {'1', 5, {0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E}},
    {'2', 5, {0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F}}, {'3', 5, {0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E}},
    {'4', 5, {0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02}}, {'5', 5, {0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E}},
    {'6', 5, {0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E}}, {'7', 5, {0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08}},
    {'8', 5, {0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E}}, {'9', 5, {0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C}},
    {'A', 5, {0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11}}, {'B', 5, {0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E}},
    {'C', 5, {0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E}}, {'D', 5, {0x1C, 0x12, 0x11, 0x11, 0x11, 0x12, 0x1C}},
    {'E', 5, {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F}}, {'F', 5, {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10}},
    {'G', 5, {0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F}}, {'H', 5, {0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11}},
    {'I', 3, {0x07, 0x02, 0x02, 0x02, 0x02, 0x02, 0x07}}, {'J', 5, {0x07, 0x02, 0x02, 0x02, 0x02, 0x12, 0x0C}},
    {'K', 5, {0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11}}, {'L', 5, {0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F}},
    {'M', 5, {0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11}}, {'N', 5, {0x11, 0x11, 0x19, 0x15, 0x13, 0x11, 0x11}},
    {'O', 5, {0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E}}, {'P', 5, {0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10}},
    {'Q', 5, {0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D}}, {'R', 5, {0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11}},
    {'S', 5, {0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E}}, {'T', 5, {0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04}},
    {'U', 5, {0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E}}, {'V', 5, {0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04}},
    {'W', 5, {0x11, 0x11, 0x11, 0x15, 0x15, 0x15, 0x0A}}, {'X', 5, {0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11}},
    {'Y', 5, {0x11, 0x11, 0x0A, 0x04, 0x04, 0x04, 0x04}}, {'Z', 5, {0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F}},
    {':', 2, {0x00, 0x03, 0x03, 0x00, 0x03, 0x03, 0x00}}, {'!', 1, {0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x01}},
    {'?', 5, {0x0E, 0x11, 0x01, 0x02, 0x04, 0x00, 0x04}}, {'.', 1, {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01}},
    {'-', 4, {0x00, 0x00, 0x00, 0x0F, 0x00, 0x00, 0x00}}, {'\'', 1, {0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00}},
    {'%', 5, {0x19, 0x1A, 0x02, 0x04, 0x08, 0x0B, 0x13}}, {'&', 5, {0x0C, 0x12, 0x14, 0x08, 0x15, 0x12, 0x0D}},
    {'\001', 3, {0x02, 0x05, 0x02, 0x00, 0x00, 0x00, 0x00}}, {' ', 3, {0, 0, 0, 0, 0, 0, 0}},
};

static const Glyph *prv_glyph(char c) {
  if (c >= 'a' && c <= 'z') c = c - 'a' + 'A';
  for (size_t i = 0; i < ARRAY_LENGTH(GLYPHS); i++) {
    if (GLYPHS[i].c == c) return &GLYPHS[i];
  }
  return NULL;
}

static bool prv_px_supported(const char *s) {
  for (; *s; s++) {
    if (!prv_glyph(*s)) return false;
  }
  return true;
}

static int prv_px_width(const char *s, int len, int scale) {
  int w = 0;
  for (int i = 0; i < len; i++) w += (prv_glyph(s[i])->w + 1) * scale;
  return w > 0 ? w - scale : 0;
}

static void prv_px_draw(GContext *ctx, const char *s, int len, int x, int y, int scale) {
  for (int i = 0; i < len; i++) {
    const Glyph *g = prv_glyph(s[i]);
    for (int row = 0; row < 7; row++) {
      for (int col = 0; col < g->w; col++) {
        if (g->rows[row] & (1 << (g->w - 1 - col))) {
          graphics_fill_rect(ctx, GRect(x + col * scale, y + row * scale, scale, scale), 0, GCornerNone);
        }
      }
    }
    x += (g->w + 1) * scale;
  }
}

#define PX_MAX_LINES 2

typedef struct {
  int scale, lines;
  int start[PX_MAX_LINES], len[PX_MAX_LINES];
} PxLayout;

/** Greedy word wrap of [s] into at most [max_lines] lines of [width] at [scale]. */
static bool prv_px_wrap(const char *s, int scale, int width, int max_lines, PxLayout *out) {
  const int n = strlen(s);
  int i = 0;
  out->lines = 0;
  out->scale = scale;
  while (i < n) {
    while (i < n && s[i] == ' ') i++;
    if (i >= n) break;
    if (out->lines == max_lines) return false;
    int end = i, fit = -1;
    while (end <= n) {
      if (end == n || s[end] == ' ') {
        if (prv_px_width(s + i, end - i, scale) <= width) fit = end;
        else break;
      }
      end++;
    }
    if (fit < 0) return false;  // a single word is too wide
    out->start[out->lines] = i;
    out->len[out->lines] = fit - i;
    out->lines++;
    i = fit;
  }
  return out->lines > 0;
}

static int prv_px_height(const PxLayout *l) { return l->lines * 9 * l->scale - 2 * l->scale; }

/** Largest scale in [min,max] at which [s] fits [width] x [height] in up to [max_lines]. */
static bool prv_px_fit(const char *s, int min_scale, int max_scale, int width, int height, int max_lines,
                       PxLayout *out) {
  for (int sc = max_scale; sc >= min_scale; sc--) {
    if (prv_px_wrap(s, sc, width, max_lines, out) && prv_px_height(out) <= height) return true;
  }
  return false;
}

static void prv_px_draw_layout(GContext *ctx, const char *s, const PxLayout *l, int cx, int y) {
  for (int i = 0; i < l->lines; i++) {
    const int w = prv_px_width(s + l->start[i], l->len[i], l->scale);
    prv_px_draw(ctx, s + l->start[i], l->len[i], cx - w / 2, y + i * 9 * l->scale, l->scale);
  }
}

// ---- pixel sprites ---------------------------------------------------------------

static const char *const FLAME_A[] = {
    ".....X......", ".....XX.....", "....XXX.....", "....XXXX....", "...XXXXX..X.", "..XXXXXXX.X.",
    "..XXX.XXXXX.", ".XXX...XXXX.", ".XX.....XXX.", ".XX..X..XXX.", "..XX.XX.XX..", "...XXXXXX...",
};
static const char *const FLAME_B[] = {
    "......X.....", ".....XX.....", ".....XXX....", "....XXXX....", ".X..XXXXX...", ".X.XXXXXXX..",
    ".XXXXX.XXX..", ".XXXX...XXX.", ".XXX.....XX.", ".XXX..X..XX.", "..XX.XX.XX..", "...XXXXXX...",
};
static const char *const CHECK[] = {
    "..........XX", ".........XXX", "........XXX.", ".......XXX..", "XX....XXX...", "XXX..XXX....",
    ".XXXXXX.....", "..XXXX......", "...XX.......",
};
static const char *const SPARKLE[] = {
    "....X.......", "....X.......", "...XXX......", "..XXXXX..X..", "XXXXXXXXXXX.", "..XXXXX..X..",
    "...XXX......", "....X.......", "....X.......",
};

static const char *const SLIDERS[] = {
    ".X...X...X.", ".X...X...X.", "XXX..X...X.", ".X...X..XXX", ".X..XXX..X.", ".X...X...X.", ".X...X...X.",
};
static const char *const SNOWFLAKE[] = {
    ".....X.....", "..X..X..X..", "...X.X.X...", "....XXX....", ".X...X...X.", "XXXXXXXXXXX",
    ".X...X...X.", "....XXX....", "...X.X.X...", "..X..X..X..", ".....X.....",
};

typedef struct {
  const char *const *rows;
  int h;
} Sprite;

#define SPRITE(a) ((Sprite){(a), ARRAY_LENGTH(a)})

static void prv_sprite_draw(GContext *ctx, Sprite s, int cx, int y, int scale) {
  const int w = strlen(s.rows[0]);
  const int x0 = cx - w * scale / 2;
  for (int r = 0; r < s.h; r++) {
    for (int c = 0; c < w; c++) {
      if (s.rows[r][c] == 'X') graphics_fill_rect(ctx, GRect(x0 + c * scale, y + r * scale, scale, scale), 0, GCornerNone);
    }
  }
}

/** Three pixel dots, one lit at a time: the "looking" animation. */
static void prv_dots_draw(GContext *ctx, int cx, int y, int scale, uint32_t frame) {
  for (int i = 0; i < 3; i++) {
    const int size = (i == (int)((frame / 2) % 3)) ? 3 * scale : 2 * scale;
    const int x = cx + (i - 1) * 5 * scale - size / 2;
    graphics_fill_rect(ctx, GRect(x, y + (3 * scale - size) / 2, size, size), 0, GCornerNone);
  }
}

/** Pixel battery: outline, nub and a fill proportional to [pct]. */
static void prv_battery_draw(GContext *ctx, int x, int y, int scale, int pct) {
  const int w = 11 * scale, h = 6 * scale;
  graphics_fill_rect(ctx, GRect(x, y, w, scale), 0, GCornerNone);
  graphics_fill_rect(ctx, GRect(x, y + h - scale, w, scale), 0, GCornerNone);
  graphics_fill_rect(ctx, GRect(x, y, scale, h), 0, GCornerNone);
  graphics_fill_rect(ctx, GRect(x + w - scale, y, scale, h), 0, GCornerNone);
  graphics_fill_rect(ctx, GRect(x + w, y + 2 * scale, scale, 2 * scale), 0, GCornerNone);
  const int clamped = pct < 0 ? 0 : pct > 100 ? 100 : pct;
  const int inner = (w - 4 * scale) * clamped / 100;
  if (inner > 0) graphics_fill_rect(ctx, GRect(x + 2 * scale, y + 2 * scale, inner, h - 4 * scale), 0, GCornerNone);
}

// ---- helpers ---------------------------------------------------------------------

static uint64_t prv_now_ms(void) {
  time_t s;
  uint16_t ms;
  time_ms(&s, &ms);
  return (uint64_t)s * 1000 + ms;
}

static bool prv_set(uint8_t bit) { return (s_settings & bit) != 0; }
static bool prv_animate(void) { return !prv_set(SET_NO_ANIM); }

static void prv_vibe_short(void) {
  if (!prv_set(SET_NO_VIBES)) vibes_short_pulse();
}
static void prv_vibe_double(void) {
  if (!prv_set(SET_NO_VIBES)) vibes_double_pulse();
}

// Session alerts: their own patterns so they're recognizable without looking.
static const uint32_t ALERT_START[] = {150, 100, 150, 100, 400};  // short-short-long: at temperature
static const uint32_t ALERT_END[] = {400, 150, 150, 100, 150};    // long-short-short: session over

static void prv_alert(const uint32_t *pattern, uint32_t count) {
  if (prv_set(SET_NO_SESSION_ALERTS)) return;
  vibes_enqueue_custom_pattern((VibePattern){.durations = pattern, .num_segments = count});
}

static void prv_apply_light(bool in_app) { light_enable(in_app && prv_set(SET_LIGHT_ON)); }

/** Converts a phone-supplied °F value to the user's unit. */
static int prv_temp(int f) { return prv_set(SET_CELSIUS) ? (f - 32) * 5 / 9 : f; }
static const char *prv_unit(void) { return prv_set(SET_CELSIUS) ? "C" : "F"; }

static void prv_anim_kick(void);

static void prv_redraw(void) {
  if (s_main_canvas) layer_mark_dirty(s_main_canvas);
  if (s_confirm_canvas) layer_mark_dirty(s_confirm_canvas);
  prv_anim_kick();
}

static bool prv_ready(void) { return s_have_msg && s_state != STATE_SEARCHING; }
static bool prv_heating(void) { return prv_ready() && (s_state == STATE_PREHEAT || s_state == STATE_ACTIVE); }

/** The profile to display: the optimistic choice if one is pending, else the device's. */
static int prv_shown_profile(void) { return s_want_profile >= 0 ? s_want_profile : s_profile; }
static bool prv_use_table(void) { return s_want_profile >= 0 && s_have_table; }
static const char *prv_shown_name(void) { return prv_use_table() ? s_p_name[s_want_profile] : s_name; }
static uint16_t prv_shown_target(void) { return prv_use_table() ? s_p_target_f[s_want_profile] : s_target_f; }
static uint16_t prv_shown_duration(void) { return prv_use_table() ? s_p_duration_s[s_want_profile] : s_duration_s; }
static uint32_t __attribute__((unused)) prv_shown_rgb(void) {
  if (s_custom_page && s_state == STATE_IDLE) return CUSTOM_RGB;
  return prv_use_table() ? s_p_rgb[s_want_profile] : s_ring_rgb;
}

#ifdef PBL_COLOR
/** Black on light ring colors, white on dark ones (perceived luminance > 0.6). */
static GColor prv_contrast(uint32_t rgb) {
  const uint32_t r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
  return (299 * r + 587 * g + 114 * b) > 153000 ? GColorBlack : GColorWhite;
}
#endif

static void prv_apply_backlight_tint(void) {
#ifdef PBL_RGB_BACKLIGHT
  if (prv_ready() && !prv_set(SET_NO_TINT)) light_set_color_rgb888(prv_shown_rgb());
  else light_set_system_color();
#endif
}

static void prv_error_timer(void *ctx) {
  s_error_timer = NULL;
  s_error[0] = '\0';
  prv_redraw();
}

/** Pops up [text] in a toast for [ms]. */
static void prv_toast(const char *text, uint32_t ms) {
  strncpy(s_error, text, sizeof(s_error) - 1);
  s_error[sizeof(s_error) - 1] = '\0';
  if (s_error_timer) app_timer_cancel(s_error_timer);
  s_error_timer = app_timer_register(ms, prv_error_timer, NULL);
  prv_redraw();
}

static void prv_show_error(const char *text) {
  prv_vibe_short();
  prv_toast(text, ERROR_SHOW_MS);
}

// ---- outbox ----------------------------------------------------------------------

static bool prv_send_dict(OutKind kind, uint32_t key, uint8_t value, bool with_settings) {
  DictionaryIterator *out;
  if (app_message_outbox_begin(&out) != APP_MSG_OK) return false;
  dict_write_uint8(out, key, value);
  if (with_settings) dict_write_uint8(out, MESSAGE_KEY_SETTINGS, s_settings);
  if (app_message_outbox_send() != APP_MSG_OK) return false;
  s_out_kind = kind;
  return true;
}

static void prv_send_profile(void) {
  if (s_want_profile < 0) return;
  if (s_out_kind != OUT_NONE) {
    s_profile_dirty = true;
    return;
  }
  s_profile_dirty = false;
  if (!prv_send_dict(OUT_PROFILE, MESSAGE_KEY_SET_PROFILE, (uint8_t)s_want_profile, false)) {
    s_want_profile = -1;
    prv_show_error("Profile not changed");
  }
}

static void prv_send_cmd(int cmd) {
  if (s_out_kind != OUT_NONE) {
    s_pending_cmd = cmd;  // goes out as soon as the outbox frees up
    return;
  }
  s_pending_cmd = 0;
  if (cmd == CMD_START_CUSTOM) {
    DictionaryIterator *out;
    bool ok = app_message_outbox_begin(&out) == APP_MSG_OK;
    if (ok) {
      dict_write_uint8(out, MESSAGE_KEY_CMD, CMD_START_CUSTOM);
      dict_write_uint16(out, MESSAGE_KEY_CUSTOM_F, (uint16_t)s_custom_f);
      dict_write_uint16(out, MESSAGE_KEY_CUSTOM_S, (uint16_t)s_custom_s);
      ok = app_message_outbox_send() == APP_MSG_OK;
    }
    if (ok) s_out_kind = OUT_CUSTOM;
    else prv_show_error("Start failed");
    return;
  }
  const OutKind kind = cmd == CMD_START ? OUT_START : cmd == CMD_ABORT ? OUT_ABORT : cmd == CMD_BOOST ? OUT_BOOST : OUT_REFRESH;
  // Refresh also carries the settings, so the phone knows them from the start.
  if (cmd == CMD_REFRESH) s_settings_dirty = false;
  if (!prv_send_dict(kind, MESSAGE_KEY_CMD, (uint8_t)cmd, cmd == CMD_REFRESH) && cmd != CMD_REFRESH) {
    prv_show_error(cmd == CMD_START ? "Start failed" : cmd == CMD_ABORT ? "Stop failed"
                   : cmd == CMD_BOOST ? "Boost failed" : "Couldn't reach phone");
  }
}

static void prv_send_settings(void) {
  if (s_out_kind != OUT_NONE) {
    s_settings_dirty = true;
    return;
  }
  s_settings_dirty = false;
  DictionaryIterator *out;
  if (app_message_outbox_begin(&out) != APP_MSG_OK) return;
  dict_write_uint8(out, MESSAGE_KEY_SETTINGS, s_settings);
  if (app_message_outbox_send() == APP_MSG_OK) s_out_kind = OUT_SETTINGS;
}

static void prv_pump_outbox(void) {
  if (s_out_kind != OUT_NONE) return;
  if (s_pending_cmd) prv_send_cmd(s_pending_cmd);
  else if (s_profile_dirty) prv_send_profile();
  else if (s_settings_dirty) prv_send_settings();
}

static void prv_echo_timer(void *ctx) {
  // The Proxy accepted the write but no state update arrived; stop overriding the display.
  s_echo_timer = NULL;
  s_want_profile = -1;
  prv_apply_backlight_tint();
  prv_redraw();
}

static void prv_outbox_sent(DictionaryIterator *iter, void *ctx) {
  const OutKind kind = s_out_kind;
  s_out_kind = OUT_NONE;
  if (kind == OUT_PROFILE && !s_profile_dirty) {
    if (s_echo_timer) app_timer_cancel(s_echo_timer);
    s_echo_timer = app_timer_register(ECHO_TIMEOUT_MS, prv_echo_timer, NULL);
  } else if (kind == OUT_START || kind == OUT_CUSTOM) {
    prv_vibe_double();
  } else if (kind == OUT_BOOST) {
    prv_vibe_short();
    prv_toast("Boost! +15s", 1500);
  }
  prv_pump_outbox();
}

static void prv_outbox_failed(DictionaryIterator *iter, AppMessageResult reason, void *ctx) {
  const OutKind kind = s_out_kind;
  s_out_kind = OUT_NONE;
  APP_LOG(APP_LOG_LEVEL_WARNING, "outbox failed kind=%d reason=%d", (int)kind, (int)reason);
  if (kind == OUT_PROFILE) {
    s_want_profile = -1;
    s_profile_dirty = false;
    prv_apply_backlight_tint();
    prv_show_error("Profile not changed");
  } else if (kind == OUT_START || kind == OUT_CUSTOM) {
    prv_show_error("Start failed");
  } else if (kind == OUT_BOOST) {
    prv_show_error("Boost failed");
  } else if (kind == OUT_ABORT) {
    prv_show_error("Stop failed");
  } else if (kind == OUT_SETTINGS) {
    s_settings_dirty = true;  // retried with the next message out
  }
  prv_pump_outbox();
}

// ---- inbox -----------------------------------------------------------------------

static int32_t prv_int(const Tuple *t) {
  if (t->type == TUPLE_INT) {
    switch (t->length) {
      case 1: return t->value->int8;
      case 2: return t->value->int16;
      default: return t->value->int32;
    }
  }
  switch (t->length) {
    case 1: return t->value->uint8;
    case 2: return t->value->uint16;
    default: return (int32_t)t->value->uint32;
  }
}

static void prv_copy(char *dst, size_t size, const Tuple *t) {
  strncpy(dst, t->value->cstring, size - 1);
  dst[size - 1] = '\0';
}

static void prv_inbox_received(DictionaryIterator *iter, void *ctx) {
  const bool was_ready = prv_ready();
  const uint8_t prev = s_state;
  Tuple *t;

  for (int i = 0; i < PROFILE_COUNT; i++) {
    if ((t = dict_find(iter, MESSAGE_KEY_P_NAME + i))) {
      prv_copy(s_p_name[i], NAME_LEN, t);
      s_have_table = true;
    }
    if ((t = dict_find(iter, MESSAGE_KEY_P_TARGET_F + i))) s_p_target_f[i] = prv_int(t);
    if ((t = dict_find(iter, MESSAGE_KEY_P_DURATION_S + i))) s_p_duration_s[i] = prv_int(t);
    if ((t = dict_find(iter, MESSAGE_KEY_P_RGB + i))) s_p_rgb[i] = (uint32_t)prv_int(t);
  }

  if ((t = dict_find(iter, MESSAGE_KEY_STATE))) s_state = prv_int(t);
  if ((t = dict_find(iter, MESSAGE_KEY_STATUS_TEXT))) prv_copy(s_status, DETAIL_LEN, t);
  if ((t = dict_find(iter, MESSAGE_KEY_PROFILE_IDX))) s_profile = prv_int(t);
  if ((t = dict_find(iter, MESSAGE_KEY_PROFILE_NAME))) prv_copy(s_name, NAME_LEN, t);
  if ((t = dict_find(iter, MESSAGE_KEY_TARGET_F))) s_target_f = prv_int(t);
  if ((t = dict_find(iter, MESSAGE_KEY_DURATION_S))) s_duration_s = prv_int(t);
  if ((t = dict_find(iter, MESSAGE_KEY_HEATER_F))) s_heater_f = prv_int(t);
  if ((t = dict_find(iter, MESSAGE_KEY_BATT_PCT))) s_batt = prv_int(t);
  if ((t = dict_find(iter, MESSAGE_KEY_RING_RGB))) s_ring_rgb = (uint32_t)prv_int(t);
  if ((t = dict_find(iter, MESSAGE_KEY_REMAIN_S))) {
    s_remain_s = prv_int(t);
    s_remain_at_ms = prv_now_ms();
  }
  s_have_msg = true;

  // The device now reports the profile we asked for: the optimistic display is confirmed.
  if (s_want_profile >= 0 && s_out_kind != OUT_PROFILE && !s_profile_dirty && s_profile == s_want_profile) {
    s_want_profile = -1;
    if (s_echo_timer) {
      app_timer_cancel(s_echo_timer);
      s_echo_timer = NULL;
    }
  }

  if (prv_ready() && s_state != STATE_IDLE) {
    s_want_profile = -1;
    if (window_stack_get_top_window() == s_confirm_window) window_stack_pop(true);
  }
  if (!prv_ready() && window_stack_get_top_window() == s_confirm_window) window_stack_pop(true);

  if (was_ready && prv_ready() && prev != s_state) {
    if (prev == STATE_IDLE && s_state == STATE_PREHEAT) {
      light_enable_interaction();  // heat started
    } else if (prev == STATE_PREHEAT && s_state == STATE_ACTIVE) {
      s_active_at_ms = prv_now_ms();
      prv_alert(ALERT_START, ARRAY_LENGTH(ALERT_START));  // at temperature: session fully started
    } else if (prev == STATE_ACTIVE && s_state == STATE_FADE) {
      light_enable_interaction();  // session complete
      prv_alert(ALERT_END, ARRAY_LENGTH(ALERT_END));
    } else if ((prev == STATE_PREHEAT || prev == STATE_ACTIVE) && s_state == STATE_IDLE) {
      light_enable_interaction();  // stopped
    }
  }

  if (s_state == STATE_COOLDOWN && prev != STATE_COOLDOWN) {
    light_enable_interaction();
    prv_vibe_double();
    if (s_status[0]) prv_toast(s_status, ERROR_SHOW_MS);
  }

  // The system drops the tint on notifications, so re-apply on every message.
  prv_apply_backlight_tint();
  prv_redraw();
}

static void prv_inbox_dropped(AppMessageResult reason, void *ctx) {
  APP_LOG(APP_LOG_LEVEL_WARNING, "inbox dropped: %d", (int)reason);
}

static void prv_refresh_timer(void *ctx) {
  s_refresh_timer = NULL;
  if (s_have_msg) return;
  prv_send_cmd(CMD_REFRESH);
  s_refresh_timer = app_timer_register(REFRESH_RETRY_MS, prv_refresh_timer, NULL);
}

static void prv_tip_timer(void *ctx) {
  s_tip_timer = NULL;
  s_show_tip = false;
  prv_redraw();
}

static void prv_tick(struct tm *tick_time, TimeUnits changed) {
  if (prv_ready() && (s_state == STATE_ACTIVE || s_state == STATE_COOLDOWN)) prv_redraw();
}

static void prv_focus(bool in_focus) {
  if (in_focus) prv_apply_backlight_tint();
  prv_apply_light(in_focus);
}

// ---- view ------------------------------------------------------------------------

typedef enum { ICON_NONE, ICON_FLAME, ICON_FLAME_FLICKER, ICON_CHECK, ICON_SPARKLE, ICON_DOTS, ICON_SNOW, ICON_SLIDERS } Icon;

// Every page has the same four text roles, each in one fixed font, so sizes never jump:
//   hero    big pixel text (or Gothic 28 for characters the pixel font lacks)
//   sub     the playful line           Gothic 24 bold
//   detail  numbers / status            Gothic 18 bold (up to two lines)
//   hint    which button to hold        Gothic 18 bold; a pill at the bottom on rectangular screens
typedef struct {
  GColor bg, fg;
  Icon icon;
  char hero[TEXT_LEN];
  char sub[TEXT_LEN];
  char detail[DETAIL_LEN];
  char hint[TEXT_LEN];
  bool battery;
  bool profile_page;  // hero is a profile name: all four names share one size
  int32_t arc;        // target permille, -1 = no progress ring
} View;

static int prv_remaining_now(void) {
  if (s_remain_s < 0) return -1;
#ifdef PUFFBLE_DEMO
  return s_remain_s;  // frozen, so screenshots on every watch show the same numbers
#endif
  const int left = s_remain_s - (int)((prv_now_ms() - s_remain_at_ms) / 1000);
  return left < 0 ? 0 : left;
}

static void prv_build_view(View *v, bool confirm) {
  memset(v, 0, sizeof(*v));
  v->arc = -1;

  if (!prv_ready()) {
    v->bg = GColorBlack;
    v->fg = GColorWhite;
    v->icon = ICON_DOTS;
    snprintf(v->hero, sizeof(v->hero), "Looking for your Proxy");
    snprintf(v->detail, sizeof(v->detail), "%s", s_have_msg ? s_status : "Waiting for phone");
    return;
  }

#ifdef PBL_COLOR
  const uint32_t rgb = prv_shown_rgb();
  v->bg = GColorFromHEX(rgb);
  v->fg = prv_contrast(rgb);
#else
  v->bg = GColorBlack;
  v->fg = GColorWhite;
#endif

  const char *u = prv_unit();
  if (confirm) {
    const bool custom = s_confirm_custom;
    const int d = custom ? s_custom_s : prv_shown_duration();
    const int t = custom ? s_custom_f : prv_shown_target();
#ifdef PBL_COLOR
    if (custom) {
      v->bg = GColorFromHEX(CUSTOM_RGB);
      v->fg = prv_contrast(CUSTOM_RGB);
    }
#endif
    v->icon = ICON_FLAME;
    snprintf(v->hero, sizeof(v->hero), "Heat?");
    snprintf(v->sub, sizeof(v->sub), "%s", custom ? "Custom" : prv_shown_name());
    snprintf(v->detail, sizeof(v->detail), "%d" DEG "%s" DOT "%d:%02d", prv_temp(t), u, d / 60, d % 60);
    snprintf(v->hint, sizeof(v->hint), "%s", prv_set(SET_START_DOUBLE) ? "Press SELECT twice" : "Hold SELECT to heat");
    return;
  }

  switch (s_state) {
    case STATE_IDLE: {
      if (s_custom_page) {
        // One-time session with your own temperature and time; nothing on the Proxy changes.
        v->icon = ICON_SLIDERS;
        v->profile_page = true;
        snprintf(v->hero, sizeof(v->hero), "Custom");
        snprintf(v->sub, sizeof(v->sub), "One-time session");
        snprintf(v->detail, sizeof(v->detail), "%d" DEG "%s" DOT "%d:%02d", prv_temp(s_custom_f), u,
                 s_custom_s / 60, s_custom_s % 60);
        if (s_show_tip) snprintf(v->hint, sizeof(v->hint), "Hold UP: settings");
        v->battery = true;
        break;
      }
      const int d = prv_shown_duration();
      v->icon = ICON_SPARKLE;
      v->profile_page = true;
      snprintf(v->hero, sizeof(v->hero), "%s", prv_shown_name());
      snprintf(v->sub, sizeof(v->sub), "Ready!");
      snprintf(v->detail, sizeof(v->detail), "%d" DEG "%s" DOT "%d:%02d", prv_temp(prv_shown_target()), u, d / 60, d % 60);
      if (s_show_tip) snprintf(v->hint, sizeof(v->hint), "Hold UP: settings");
      v->battery = true;
      break;
    }
    case STATE_PREHEAT: {
      v->icon = ICON_FLAME_FLICKER;
      const int shown = prv_animate() ? s_temp_shown : s_heater_f;
      snprintf(v->hero, sizeof(v->hero), "%d" PX_DEG, prv_temp(shown));
      snprintf(v->sub, sizeof(v->sub), "Heating up!");
      snprintf(v->detail, sizeof(v->detail), "Target %d" DEG "%s", prv_temp(s_target_f), u);
      snprintf(v->hint, sizeof(v->hint), "Hold SELECT to stop");
      const uint32_t pm = s_target_f ? (uint32_t)s_heater_f * 1000 / s_target_f : 0;
      v->arc = pm > 1000 ? 1000 : pm;
      break;
    }
    case STATE_ACTIVE: {
      v->icon = ICON_FLAME;
      const int left = prv_remaining_now();
      if (left < 0) {
        snprintf(v->hero, sizeof(v->hero), "ON");
        v->arc = 1000;
      } else {
        snprintf(v->hero, sizeof(v->hero), "%d:%02d", left / 60, left % 60);
        v->arc = s_duration_s ? (int32_t)left * 1000 / s_duration_s : 0;
        if (v->arc > 1000) v->arc = 1000;
      }
      snprintf(v->sub, sizeof(v->sub), "Enjoy!");
      if (s_active_at_ms && prv_now_ms() - s_active_at_ms < BOOST_TIP_MS) {
        snprintf(v->detail, sizeof(v->detail), "SELECT twice: boost");
      } else {
        snprintf(v->detail, sizeof(v->detail), "Heater %d" DEG "%s", prv_temp(s_heater_f), u);
      }
      snprintf(v->hint, sizeof(v->hint), "Hold SELECT to stop");
      break;
    }
    case STATE_COOLDOWN: {
      // Icy page whatever the profile: the gauge drains over the suggested wait.
#ifdef PBL_COLOR
      v->bg = GColorFromHEX(0x55AAFF);
      v->fg = GColorBlack;
#endif
      v->icon = ICON_SNOW;
      const int left = prv_remaining_now();
      if (s_heater_f) snprintf(v->hero, sizeof(v->hero), "%d" PX_DEG, prv_temp(s_heater_f));
      else snprintf(v->hero, sizeof(v->hero), "Cool");
      snprintf(v->sub, sizeof(v->sub), "Cooling off");
      if (left > 0) snprintf(v->detail, sizeof(v->detail), "Try again in %d:%02d", left / 60, left % 60);
      else snprintf(v->detail, sizeof(v->detail), "Ready to try again");
      snprintf(v->hint, sizeof(v->hint), "Hold SELECT to skip");
      v->arc = (s_duration_s && left > 0) ? (int32_t)left * 1000 / s_duration_s : 0;
      if (v->arc > 1000) v->arc = 1000;
      break;
    }
    case STATE_FADE:
      v->icon = ICON_CHECK;
      snprintf(v->hero, sizeof(v->hero), "All done");
      snprintf(v->sub, sizeof(v->sub), "Cooling down");
      v->arc = 1000;
      break;
    default:
      snprintf(v->hero, sizeof(v->hero), "%s", s_name);
      snprintf(v->sub, sizeof(v->sub), "Busy");
      break;
  }
}

// ---- animation -------------------------------------------------------------------

static int32_t prv_ease(int32_t shown, int32_t target) {
  const int32_t step = (target - shown) / 4;
  return step != 0 ? shown + step : target;
}

/** One animation frame. Returns true while anything is still moving. */
static bool prv_anim_step(void) {
  View v;
  prv_build_view(&v, false);
  bool moving = false;

  if (s_state != s_anim_state) {
    // New phase: the ring sweeps in from empty and the temperature counts up from zero.
    if (prv_ready() && s_state == STATE_PREHEAT) s_temp_shown = 0;
    if (s_state == STATE_FADE) s_fade_level = 1000;
    s_arc_shown = v.arc >= 0 ? 0 : -1;
    s_anim_state = s_state;
  }

  if (!prv_animate()) {
    s_arc_shown = v.arc;
    s_temp_shown = s_heater_f;
    s_slide_from = -1;
    s_fade_level = 0;
    return false;
  }

  if (v.arc < 0) {
    s_arc_shown = -1;
  } else {
    if (s_arc_shown < 0) s_arc_shown = 0;
    const int32_t next = prv_ease(s_arc_shown, v.arc);
    moving |= next != s_arc_shown;
    s_arc_shown = next;
  }

  const int32_t next_temp = prv_ease(s_temp_shown, s_heater_f);
  moving |= next_temp != s_temp_shown && s_state == STATE_PREHEAT;
  s_temp_shown = next_temp;

  // Page swipe between profiles (~240 ms).
  if (s_slide_from >= 0) {
    s_slide_p += 170;
    if (s_slide_p >= 1000) s_slide_from = -1;
    moving = true;
  }

  // "All done": the full ring thins away after the sweep.
  if (s_state == STATE_FADE && s_fade_level > 0 && s_arc_shown >= 1000) {
    s_fade_level -= 40;
    if (s_fade_level < 0) s_fade_level = 0;
    moving = true;
  } else if (s_state == STATE_FADE && s_fade_level > 0) {
    moving = true;
  }
  return moving;
}

static bool prv_flickers(void) { return !prv_ready() || s_state == STATE_PREHEAT; }

static void prv_anim_timer(void *ctx) {
  s_anim_timer = NULL;
  s_frame++;
  const bool moving = prv_anim_step();
  if (s_main_canvas) layer_mark_dirty(s_main_canvas);
  if (s_confirm_canvas) layer_mark_dirty(s_confirm_canvas);
  if (!prv_animate()) return;
  if (moving) s_anim_timer = app_timer_register(FRAME_MS, prv_anim_timer, NULL);
  else if (prv_flickers()) s_anim_timer = app_timer_register(FLICKER_MS, prv_anim_timer, NULL);
}

static void prv_anim_kick(void) {
  if (!s_anim_timer) s_anim_timer = app_timer_register(prv_animate() ? FRAME_MS : 1, prv_anim_timer, NULL);
}

// ---- drawing ---------------------------------------------------------------------

static void prv_draw_ring(GContext *ctx, GRect b, int edge, int thick, int32_t permille) {
  const GRect ring = grect_inset(b, GEdgeInsets(edge));
  // Pixel track: small squares all the way round, like watch-face ticks.
  const int r = (ring.size.w < ring.size.h ? ring.size.w : ring.size.h) / 2 - 5;
  const GPoint c = grect_center_point(&ring);
  for (int i = 0; i < 36; i++) {
    const int32_t a = TRIG_MAX_ANGLE * i / 36;
    const int x = c.x + sin_lookup(a) * r / TRIG_MAX_RATIO, y = c.y - cos_lookup(a) * r / TRIG_MAX_RATIO;
    graphics_fill_rect(ctx, GRect(x - 1, y - 1, 3, 3), 0, GCornerNone);
  }
  if (permille <= 0 || thick <= 0) return;
  // Stepped, not smooth: the arc fills in 2.5% blocks for a pixel look.
  const int32_t steps = (permille + 24) / 25;
  graphics_fill_radial(ctx, ring, GOvalScaleModeFitCircle, thick, 0, TRIG_MAX_ANGLE * steps / 40);
}

/**
 * Rectangular progress: a pixel track that runs clockwise round the screen edge from the
 * top center, so text gets the full width (a circle on a rectangle squeezed the lines).
 */
static void prv_draw_rect_ring(GContext *ctx, GRect b, int inset, int thick, int32_t permille) {
  const GRect r = grect_inset(b, GEdgeInsets(inset));
  const int w = r.size.w, h = r.size.h, x0 = r.origin.x, y0 = r.origin.y, cx = x0 + w / 2;
  const int total = 2 * w + 2 * h;

  // Track: a dot every 1/40th of the way round, on the track's center line.
  for (int i = 0; i < 40; i++) {
    int d = total * i / 40, x, y;
    const int c = thick / 2;
    if (d < w / 2) { x = cx + d; y = y0 + c; }
    else if ((d -= w / 2) < h) { x = x0 + w - 1 - c; y = y0 + d; }
    else if ((d -= h) < w) { x = x0 + w - 1 - d; y = y0 + h - 1 - c; }
    else if ((d -= w) < h) { x = x0 + c; y = y0 + h - 1 - d; }
    else { d -= h; x = x0 + d; y = y0 + c; }
    graphics_fill_rect(ctx, GRect(x - 1, y - 1, 3, 3), 0, GCornerNone);
  }
  if (permille <= 0 || thick <= 0) return;

  // Stepped fill in 2.5% blocks, like the round ring.
  int left = total * ((permille + 24) / 25) / 40;
  int n = left < w / 2 ? left : w / 2;
  graphics_fill_rect(ctx, GRect(cx, y0, n, thick), 0, GCornerNone);
  left -= n;
  n = left < h ? left : h;
  if (n > 0) graphics_fill_rect(ctx, GRect(x0 + w - thick, y0, thick, n), 0, GCornerNone);
  left -= n;
  n = left < w ? left : w;
  if (n > 0) graphics_fill_rect(ctx, GRect(x0 + w - n, y0 + h - thick, n, thick), 0, GCornerNone);
  left -= n;
  n = left < h ? left : h;
  if (n > 0) graphics_fill_rect(ctx, GRect(x0, y0 + h - n, thick, n), 0, GCornerNone);
  left -= n;
  n = left < w / 2 ? left : w / 2;
  if (n > 0) graphics_fill_rect(ctx, GRect(x0, y0, n, thick), 0, GCornerNone);
}

/**
 * The one pixel scale every profile name uses, so swiping between profiles never changes
 * the text size: the largest scale at which all four names fit. 0 if unknown.
 */
static int prv_profile_scale(int width, int max_h) {
  if (!s_have_table) return 0;
  int common = 99;
  for (int i = 0; i < PROFILE_COUNT; i++) {
    PxLayout l;
    if (!prv_px_supported(s_p_name[i]) || !prv_px_fit(s_p_name[i], 2, 5, width, max_h, PX_MAX_LINES, &l)) continue;
    if (l.scale < common) common = l.scale;
  }
  return common == 99 ? 0 : common;
}

/** Text drawn centered in a box no wider than the ring's chord at that height (round only). */
static void prv_line(GContext *ctx, const char *text, GFont font, GRect full, int y, int h, int width, bool round,
                     int ring_r) {
  int w = width;
  if (round) {
    const int mid = full.origin.y + full.size.h / 2;
    const int dy = ABS(y - mid) > ABS(y + h - mid) ? ABS(y - mid) : ABS(y + h - mid);
    const int chord = dy < ring_r ? 2 * prv_isqrt(ring_r * ring_r - dy * dy) : width;
    if (chord < w) w = chord;
  }
  const int cx = full.origin.x + full.size.w / 2;
  graphics_draw_text(ctx, text, font, GRect(cx - w / 2, y, w, h), GTextOverflowModeTrailingEllipsis,
                     GTextAlignmentCenter, NULL);
}

/**
 * Pixel-font text bent along the bottom of a circle, reading left to right, each glyph's top
 * toward the center (like printing around a watch bezel).
 * [radius] is the baseline (glyph bottoms).
 */
static int32_t prv_rdiv(int32_t a, int32_t b) { return (a >= 0 ? a + b / 2 : a - b / 2) / b; }

static void prv_px_draw_arc(GContext *ctx, const char *text, GPoint center, int radius, int scale) {
  const int len = strlen(text);
  const int total = prv_px_width(text, len, scale);
  const int32_t two_t = 2 * TRIG_MAX_RATIO;
  int pos = 0;  // distance along the baseline to the current glyph's left edge
  for (int i = 0; i < len; i++) {
    const Glyph *g = prv_glyph(text[i]);
    if (!g) continue;
    const int gw = g->w * scale;
    // Each glyph is rotated as one rigid block to the angle of its center along the arc
    // (bending every pixel separately fans the letters apart). Distances are doubled to
    // keep half-pixel centers in integer math.
    const int32_t mid2 = 2 * pos + gw - total;
    const int32_t a = mid2 * TRIG_MAX_ANGLE / (4 * 31416 * radius / 10000);
    const int32_t sn = sin_lookup(a), cs = cos_lookup(a);
    for (int row = 0; row < 7; row++) {
      for (int col = 0; col < g->w; col++) {
        if (!(g->rows[row] & (1 << (g->w - 1 - col)))) continue;
        for (int sy = 0; sy < scale; sy++) {
          for (int sx = 0; sx < scale; sx++) {
            const int32_t lx2 = 2 * (col * scale + sx) + 1 - gw;    // along the tangent
            const int32_t ly2 = 2 * ((6 - row) * scale + sy) + 1;   // up, toward the center
            const int x = center.x + prv_rdiv(2 * sn * radius + cs * lx2 - sn * ly2, two_t);
            const int y = center.y + prv_rdiv(2 * cs * radius - sn * lx2 - cs * ly2, two_t);
            graphics_fill_rect(ctx, GRect(x, y, 1, 1), 0, GCornerNone);
          }
        }
      }
    }
    pos += (g->w + 1) * scale;
  }
}

/** Draws one full page into [full] shifted down by [oy] (the swipe transition moves pages). */
static void prv_draw_page(GContext *ctx, GRect full, const View *vp, bool confirm, int oy, bool live) {
  const View v = *vp;
  const GRect b = GRect(full.origin.x, full.origin.y + oy, full.size.w, full.size.h);
  const int top = b.origin.y;
  const bool round = PBL_IF_ROUND_ELSE(true, false);

  graphics_context_set_fill_color(ctx, v.bg);
  graphics_fill_rect(ctx, b, 0, GCornerNone);
  graphics_context_set_text_color(ctx, v.fg);
  graphics_context_set_fill_color(ctx, v.fg);

  // Progress: a ring on round screens, a border track on rectangular ones.
  const int edge = round ? 4 : 3;
  const int thick = round ? 10 : 6;
  if (!confirm) {
    const int32_t arc = (live && prv_animate()) ? s_arc_shown : v.arc;
    int ring_thick = thick;
    if (live && s_state == STATE_FADE) ring_thick = prv_animate() ? thick * s_fade_level / 1000 : 0;
    if (arc >= 0 && ring_thick > 0) {
      if (round) prv_draw_ring(ctx, b, edge, ring_thick, arc);
      else prv_draw_rect_ring(ctx, b, edge, ring_thick, arc);
    }
  }

  const GFont sub_font = fonts_get_system_font(FONT_KEY_GOTHIC_24_BOLD);
  const GFont small_font = fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD);
  const int cx = b.size.w / 2;
  const int side = round ? edge + thick + 22 : edge + thick + 6;
  const int width = b.size.w - 2 * side;
  const int ring_r = (b.size.w < b.size.h ? b.size.w : b.size.h) / 2 - edge - thick - 4;

  // Vertical space: inside the border, and above the hint pill on rectangular screens.
  const bool hint_pill = !round && v.hint[0];
  const bool hint_arc = round && v.hint[0] && prv_px_supported(v.hint);
  const int pill_h = 18;
  const int arc_scale = 2;
  const int arc_band = 7 * arc_scale + 10;  // curved hint height plus breathing room
  const int area_top = top + (round ? 0 : edge + thick + (v.battery ? 14 : 2));
  const int area_bot = top + b.size.h - (round ? (hint_arc ? edge + thick + arc_band : 0)
                                               : edge + thick + (hint_pill ? pill_h + 4 : 2));

  // Icon.
  const int icon_scale = b.size.w >= 200 ? 3 : 2;
  Sprite sprite = SPRITE(SPARKLE);
  switch (v.icon) {
    case ICON_FLAME: sprite = SPRITE(FLAME_A); break;
    case ICON_FLAME_FLICKER: sprite = ((s_frame / 2) % 2) ? SPRITE(FLAME_B) : SPRITE(FLAME_A); break;
    case ICON_CHECK: sprite = SPRITE(CHECK); break;
    case ICON_SPARKLE: sprite = SPRITE(SPARKLE); break;
    case ICON_SNOW: sprite = SPRITE(SNOWFLAKE); break;
    case ICON_SLIDERS: sprite = SPRITE(SLIDERS); break;
    case ICON_DOTS: case ICON_NONE: break;
  }
  const int icon_h = v.icon == ICON_DOTS ? 3 * icon_scale : v.icon == ICON_NONE ? 0 : sprite.h * icon_scale;

  // Fixed-height text rows.
  const int gap = b.size.h >= 220 ? 8 : 5;
  const int sub_h = v.sub[0] ? 28 : 0;
  const GSize detail_size = v.detail[0] ? graphics_text_layout_get_content_size(
                                              v.detail, small_font, GRect(0, 0, width, 66),
                                              GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter)
                                        : GSize(0, 0);
  const int detail_h = v.detail[0] ? detail_size.h + 4 : 0;
  const int hint_h = (v.hint[0] && !hint_pill && !hint_arc) ? 22 : 0;
  const int fixed_h = (icon_h ? icon_h + gap : 0) + gap + sub_h + detail_h + hint_h;
  int hero_max_h = (area_bot - area_top) - fixed_h - (round ? 40 : 4);
  if (hero_max_h > 84) hero_max_h = 84;

  // Hero size: profile names share one scale; everything else takes the largest that fits.
  PxLayout px;
  const bool numeric = v.hero[0] >= '0' && v.hero[0] <= '9';
  bool pixel = false;
  if (prv_px_supported(v.hero)) {
    const int common = v.profile_page ? prv_profile_scale(width, hero_max_h) : 0;
    pixel = common ? prv_px_fit(v.hero, common, common, width, hero_max_h, PX_MAX_LINES, &px)
                   : prv_px_fit(v.hero, 2, numeric ? 9 : 5, width, hero_max_h, numeric ? 1 : PX_MAX_LINES, &px);
  }
  const GFont hero_font = fonts_get_system_font(FONT_KEY_GOTHIC_28_BOLD);
  const int hero_h = pixel ? prv_px_height(&px)
                           : graphics_text_layout_get_content_size(v.hero, hero_font, GRect(0, 0, width, hero_max_h),
                                                                   GTextOverflowModeTrailingEllipsis,
                                                                   GTextAlignmentCenter).h;

  const int block_h = fixed_h + hero_h;
  int y = area_top + ((area_bot - area_top) - block_h) / 2;

  if (icon_h) {
    if (v.icon == ICON_DOTS) prv_dots_draw(ctx, cx, y, icon_scale, s_frame);
    else prv_sprite_draw(ctx, sprite, cx, y, icon_scale);
    y += icon_h + gap;
  }
  if (pixel) {
    prv_px_draw_layout(ctx, v.hero, &px, cx, y);
  } else {
    graphics_draw_text(ctx, v.hero, hero_font, GRect(side, y - 6, width, hero_h + 8),
                       GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
  }
  y += hero_h + gap;
  if (sub_h) {
    prv_line(ctx, v.sub, sub_font, b, y - 4, sub_h + 4, width, round, ring_r);
    y += sub_h;
  }
  if (detail_h) {
    prv_line(ctx, v.detail, small_font, b, y - 2, detail_h, width, round, ring_r);
    y += detail_h;
  }
  if (hint_h) prv_line(ctx, v.hint, small_font, b, y, hint_h, width, round, ring_r);

  // Proxy battery: top-right inside the border on rectangular screens, top-center on round ones.
  if (v.battery) {
    const int bs = 2;
    if (round) {
      // Icon and percentage side by side, centered at the top.
      char pct[6];
      snprintf(pct, sizeof(pct), "%u%%", s_batt);
      const GFont f = fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD);
      const int tw = graphics_text_layout_get_content_size(pct, f, GRect(0, 0, 60, 16),
                                                           GTextOverflowModeTrailingEllipsis, GTextAlignmentLeft).w;
      const int total = 12 * bs + 4 + tw, x0 = cx - total / 2, by = top + edge + thick + 8;
      prv_battery_draw(ctx, x0, by, bs, s_batt);
      graphics_draw_text(ctx, pct, f, GRect(x0 + 12 * bs + 4, by - 5, tw + 2, 16), GTextOverflowModeTrailingEllipsis,
                         GTextAlignmentLeft, NULL);
    } else {
      const int bx = b.size.w - edge - thick - 4 - 12 * bs, by = top + edge + thick + 3;
      char pct[6];
      snprintf(pct, sizeof(pct), "%u%%", s_batt);
      graphics_draw_text(ctx, pct, fonts_get_system_font(FONT_KEY_GOTHIC_14_BOLD), GRect(bx - 40, by - 5, 37, 16),
                         GTextOverflowModeTrailingEllipsis, GTextAlignmentRight, NULL);
      prv_battery_draw(ctx, bx, by, bs, s_batt);
    }
  }

  if (hint_arc) {
    const GPoint c = GPoint(b.origin.x + b.size.w / 2, top + b.size.h / 2);
    const int r = (b.size.w < b.size.h ? b.size.w : b.size.h) / 2 - edge - thick - 6;
    graphics_context_set_fill_color(ctx, v.fg);
    prv_px_draw_arc(ctx, v.hint, c, r, arc_scale);
  }

  // Inverted pill just above the bottom border: background-colored text on a solid block.
  if (hint_pill) {
    const GSize ts = graphics_text_layout_get_content_size(v.hint, small_font, GRect(0, 0, b.size.w, 24),
                                                           GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter);
    const int max_w = b.size.w - 2 * (edge + thick + 4);
    const int pw = ts.w + 16 > max_w ? max_w : ts.w + 16;
    const GRect pill = GRect(cx - pw / 2, top + b.size.h - edge - thick - 3 - pill_h, pw, pill_h);
    graphics_context_set_fill_color(ctx, v.fg);
    graphics_fill_rect(ctx, pill, 6, GCornersAll);
    graphics_context_set_text_color(ctx, v.bg);
    graphics_draw_text(ctx, v.hint, small_font, GRect(pill.origin.x, pill.origin.y - 4, pw, pill_h + 4),
                       GTextOverflowModeTrailingEllipsis, GTextAlignmentCenter, NULL);
  }
}

/** Ease-in-out (smoothstep) on a 0..1000 scale. */
static int32_t prv_smooth(int32_t p) { return p * p / 1000 * (3000 - 2 * p) / 1000; }

/** Toast: a solid pop-up box in the middle of the screen, over everything else. */
static void prv_draw_toast(GContext *ctx, GRect b, GColor fg, GColor bg) {
  if (!s_error[0]) return;
  const GFont f = fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD);
  const int max_w = b.size.w - PBL_IF_ROUND_ELSE(60, 24);
  const GSize ts = graphics_text_layout_get_content_size(s_error, f, GRect(0, 0, max_w - 16, 60),
                                                         GTextOverflowModeWordWrap, GTextAlignmentCenter);
  const int w = ts.w + 20, h = ts.h + 14;
  const GRect box = GRect((b.size.w - w) / 2, (b.size.h - h) / 2, w, h);
  graphics_context_set_fill_color(ctx, bg);
  graphics_fill_rect(ctx, grect_inset(box, GEdgeInsets(-2)), 10, GCornersAll);
  graphics_context_set_fill_color(ctx, fg);
  graphics_fill_rect(ctx, box, 8, GCornersAll);
  graphics_context_set_text_color(ctx, bg);
  graphics_draw_text(ctx, s_error, f, GRect(box.origin.x + 10, box.origin.y + 3, w - 20, h - 6),
                     GTextOverflowModeWordWrap, GTextAlignmentCenter, NULL);
}

static void prv_draw(Layer *layer, GContext *ctx, bool confirm) {
  const GRect b = layer_get_bounds(layer);
  View v;
  prv_build_view(&v, confirm);

  if (confirm || s_slide_from < 0) {
    prv_draw_page(ctx, b, &v, confirm, 0, true);
  } else {
    // Classic Pebble page swipe: the old profile's page slides out as the new one slides in.
    const int32_t e = prv_smooth(s_slide_p);
    const int h = b.size.h;
    View old;
    const int keep = s_want_profile;
    const bool keep_custom = s_custom_page;
    s_custom_page = s_slide_from == CUSTOM_PAGE;
    if (!s_custom_page) s_want_profile = s_slide_from;
    prv_build_view(&old, false);
    s_want_profile = keep;
    s_custom_page = keep_custom;
    prv_draw_page(ctx, b, &old, false, -s_slide_dir * h * e / 1000, false);
    prv_draw_page(ctx, b, &v, false, s_slide_dir * h * (1000 - e) / 1000, true);
  }
  prv_draw_toast(ctx, b, v.fg, v.bg);
}

static void prv_main_draw(Layer *layer, GContext *ctx) { prv_draw(layer, ctx, false); }
static void prv_confirm_draw(Layer *layer, GContext *ctx) { prv_draw(layer, ctx, true); }

// ---- settings menu ---------------------------------------------------------------

typedef struct {
  const char *title;
  uint8_t bit;  // 0 = not a toggle
} SettingRow;

static const SettingRow SETTING_ROWS[] = {
    {"Temperature", SET_CELSIUS},
    {"Start heating", SET_START_DOUBLE},
    {"Session alerts", SET_NO_SESSION_ALERTS},
    {"Button buzz", SET_NO_VIBES},
    {"Light always on", SET_LIGHT_ON},
    {"Animations", SET_NO_ANIM},
    {"Timeline pins", SET_NO_PINS},
#ifdef PBL_RGB_BACKLIGHT
    {"Backlight color", SET_NO_TINT},
#endif
    {"About", 0},
};

static uint16_t prv_menu_rows(MenuLayer *m, uint16_t section, void *ctx) { return ARRAY_LENGTH(SETTING_ROWS); }

static void prv_menu_draw_row(GContext *ctx, const Layer *cell, MenuIndex *idx, void *data) {
  const SettingRow *row = &SETTING_ROWS[idx->row];
  const char *value;
  if (row->bit == SET_CELSIUS) value = prv_set(SET_CELSIUS) ? DEG "C" : DEG "F";
  else if (row->bit == SET_LIGHT_ON) value = prv_set(SET_LIGHT_ON) ? "On" : "Off";
  else if (row->bit == SET_START_DOUBLE) value = prv_set(SET_START_DOUBLE) ? "Press SELECT twice" : "Hold SELECT";
  else if (row->bit) value = prv_set(row->bit) ? "Off" : "On";
  else value = "Puffble 0.6";
  menu_cell_basic_draw(ctx, cell, row->title, value, NULL);
}

static void prv_menu_select(MenuLayer *m, MenuIndex *idx, void *data) {
  const SettingRow *row = &SETTING_ROWS[idx->row];
  if (!row->bit) return;
  s_settings ^= row->bit;
  persist_write_int(PERSIST_SETTINGS, s_settings);
  menu_layer_reload_data(m);
  prv_apply_backlight_tint();
  prv_apply_light(true);
  if (row->bit == SET_NO_PINS) prv_send_settings();
  prv_redraw();
}

static void prv_settings_load(Window *w) {
  Layer *root = window_get_root_layer(w);
  s_menu = menu_layer_create(layer_get_bounds(root));
  menu_layer_set_callbacks(s_menu, NULL,
                           (MenuLayerCallbacks){
                               .get_num_rows = prv_menu_rows,
                               .draw_row = prv_menu_draw_row,
                               .select_click = prv_menu_select,
                           });
#ifdef PBL_COLOR
  const uint32_t rgb = prv_ready() ? prv_shown_rgb() : 0x555555;
  menu_layer_set_highlight_colors(s_menu, GColorFromHEX(rgb), prv_contrast(rgb));
#endif
  menu_layer_set_click_config_onto_window(s_menu, w);
  layer_add_child(root, menu_layer_get_layer(s_menu));
}

static void prv_settings_unload(Window *w) {
  menu_layer_destroy(s_menu);
  s_menu = NULL;
}

// ---- buttons ---------------------------------------------------------------------

static void prv_step_profile(int delta) {
  if (!prv_ready() || s_state != STATE_IDLE) return;
  // Pages: the four stored profiles, then the custom one-time page.
  const int old = s_custom_page ? CUSTOM_PAGE : prv_shown_profile();
  const int next = (old + PAGE_COUNT + delta) % PAGE_COUNT;
  s_error[0] = '\0';
  if (prv_animate() && s_have_table) {
    s_slide_from = old;  // the new page swipes in from the side you pressed
    s_slide_dir = delta;
    s_slide_p = 0;
  }
  if (next == CUSTOM_PAGE) {
    s_custom_page = true;  // local only: nothing is sent until you start a custom session
  } else {
    s_custom_page = false;
    s_want_profile = next;
    if (s_echo_timer) {
      app_timer_cancel(s_echo_timer);
      s_echo_timer = NULL;
    }
  }
  prv_apply_backlight_tint();
  prv_redraw();  // show it now; the Proxy write follows
  if (next != CUSTOM_PAGE) prv_send_profile();
}

static void prv_up(ClickRecognizerRef r, void *ctx) { prv_step_profile(-1); }
static void prv_down(ClickRecognizerRef r, void *ctx) { prv_step_profile(1); }

static void prv_up_long(ClickRecognizerRef r, void *ctx) {
  if (prv_heating()) return;
  s_show_tip = false;
  window_stack_push(s_settings_window, true);
}

static void prv_select(ClickRecognizerRef r, void *ctx) {
  const uint64_t now = prv_now_ms();
  if (prv_ready() && s_state == STATE_IDLE) {
    if (s_custom_page) {
      s_edit_field = 0;
      window_stack_push(s_edit_window, true);
    } else {
      s_confirm_custom = false;
      window_stack_push(s_confirm_window, true);
    }
  } else if (prv_ready() && s_state == STATE_COOLDOWN) {
    prv_toast("Cooling off. Hold SELECT to skip", 2000);
  } else if (prv_ready() && s_state == STATE_ACTIVE) {
    // Two quick presses during a session: boost.
    if (now - s_last_select_ms < DOUBLE_PRESS_MS) {
      s_last_select_ms = 0;
      prv_send_cmd(CMD_BOOST);
      return;
    }
  }
  s_last_select_ms = now;
}

static void prv_select_long(ClickRecognizerRef r, void *ctx) {
  if (prv_ready() && s_state == STATE_COOLDOWN) {
    prv_send_cmd(CMD_SKIP_COOLDOWN);
    return;
  }
  if (!prv_heating()) return;
  prv_send_cmd(CMD_ABORT);
  prv_vibe_short();
}

/** BACK must be pressed twice to leave, so a stray press doesn't drop you out mid-session. */
static void prv_back(ClickRecognizerRef r, void *ctx) {
  if (click_number_of_clicks_counted(r) >= 2) {
    window_stack_pop_all(true);
    return;
  }
  prv_toast("Press BACK twice to exit", 1800);
}

static void prv_main_clicks(void *ctx) {
  window_multi_click_subscribe(BUTTON_ID_BACK, 1, 2, 350, true, prv_back);
  window_single_click_subscribe(BUTTON_ID_UP, prv_up);
  window_long_click_subscribe(BUTTON_ID_UP, LONG_PRESS_MS, prv_up_long, NULL);
  window_single_click_subscribe(BUTTON_ID_DOWN, prv_down);
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select);
  window_long_click_subscribe(BUTTON_ID_SELECT, LONG_PRESS_MS, prv_select_long, NULL);
}

static void prv_confirm_start(ClickRecognizerRef r, void *ctx) {
  const bool custom = s_confirm_custom;
  if (prv_ready() && s_state == STATE_IDLE) prv_send_cmd(custom ? CMD_START_CUSTOM : CMD_START);
  window_stack_pop(true);
  if (custom) window_stack_remove(s_edit_window, false);  // back to the main screen
}

/** Start gesture is a setting: hold SELECT (default) or press it twice. */
static void prv_confirm_clicks(void *ctx) {
  if (prv_set(SET_START_DOUBLE)) {
    window_multi_click_subscribe(BUTTON_ID_SELECT, 2, 2, DOUBLE_PRESS_MS, true, prv_confirm_start);
  } else {
    window_long_click_subscribe(BUTTON_ID_SELECT, LONG_PRESS_MS, prv_confirm_start, NULL);
  }
}

// ---- custom session editor ----------------------------------------------------------------

static void prv_edit_draw(Layer *layer, GContext *ctx) {
  const GRect b = layer_get_bounds(layer);
  View v;
  memset(&v, 0, sizeof(v));
  v.arc = -1;
#ifdef PBL_COLOR
  v.bg = GColorFromHEX(CUSTOM_RGB);
  v.fg = prv_contrast(CUSTOM_RGB);
#else
  v.bg = GColorBlack;
  v.fg = GColorWhite;
#endif
  v.icon = ICON_SLIDERS;
  if (s_edit_field == 0) {
    snprintf(v.hero, sizeof(v.hero), "%d" PX_DEG, prv_temp(s_custom_f));
    snprintf(v.sub, sizeof(v.sub), "Temperature");
    snprintf(v.hint, sizeof(v.hint), "SELECT for time");
  } else {
    snprintf(v.hero, sizeof(v.hero), "%d:%02d", s_custom_s / 60, s_custom_s % 60);
    snprintf(v.sub, sizeof(v.sub), "Session time");
    snprintf(v.hint, sizeof(v.hint), "SELECT when ready");
  }
  snprintf(v.detail, sizeof(v.detail), "UP and DOWN to change");
  prv_draw_page(ctx, b, &v, true, 0, false);
  prv_draw_toast(ctx, b, v.fg, v.bg);
}

static void prv_edit_step(int delta) {
  if (s_edit_field == 0) {
    s_custom_f += delta * 5;
    if (s_custom_f < CUSTOM_F_MIN) s_custom_f = CUSTOM_F_MIN;
    if (s_custom_f > CUSTOM_F_MAX) s_custom_f = CUSTOM_F_MAX;
  } else {
    s_custom_s += delta * 5;
    if (s_custom_s < CUSTOM_S_MIN) s_custom_s = CUSTOM_S_MIN;
    if (s_custom_s > CUSTOM_S_MAX) s_custom_s = CUSTOM_S_MAX;
  }
  if (s_edit_canvas) layer_mark_dirty(s_edit_canvas);
  if (s_main_canvas) layer_mark_dirty(s_main_canvas);
}

static void prv_edit_up(ClickRecognizerRef r, void *ctx) { prv_edit_step(1); }
static void prv_edit_down(ClickRecognizerRef r, void *ctx) { prv_edit_step(-1); }

static void prv_edit_select(ClickRecognizerRef r, void *ctx) {
  if (s_edit_field == 0) {
    s_edit_field = 1;
    if (s_edit_canvas) layer_mark_dirty(s_edit_canvas);
    return;
  }
  persist_write_int(PERSIST_CUSTOM_F, s_custom_f);
  persist_write_int(PERSIST_CUSTOM_S, s_custom_s);
  if (!(prv_ready() && s_state == STATE_IDLE)) {
    prv_toast("Proxy isn't ready", 2000);
    return;
  }
  s_confirm_custom = true;
  window_stack_push(s_confirm_window, true);
}

static void prv_edit_back(ClickRecognizerRef r, void *ctx) {
  if (s_edit_field == 1) {
    s_edit_field = 0;  // back from time to temperature
    if (s_edit_canvas) layer_mark_dirty(s_edit_canvas);
    return;
  }
  persist_write_int(PERSIST_CUSTOM_F, s_custom_f);
  persist_write_int(PERSIST_CUSTOM_S, s_custom_s);
  window_stack_pop(true);
}

static void prv_edit_clicks(void *ctx) {
  window_single_repeating_click_subscribe(BUTTON_ID_UP, 120, prv_edit_up);
  window_single_repeating_click_subscribe(BUTTON_ID_DOWN, 120, prv_edit_down);
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_edit_select);
  window_single_click_subscribe(BUTTON_ID_BACK, prv_edit_back);
}

// ---- windows ---------------------------------------------------------------------

static Layer *prv_add_canvas(Window *window, LayerUpdateProc proc) {
  Layer *root = window_get_root_layer(window);
  Layer *canvas = layer_create(layer_get_bounds(root));
  layer_set_update_proc(canvas, proc);
  layer_add_child(root, canvas);
  return canvas;
}

static void prv_main_load(Window *w) { s_main_canvas = prv_add_canvas(w, prv_main_draw); }
static void prv_main_unload(Window *w) {
  layer_destroy(s_main_canvas);
  s_main_canvas = NULL;
}
static void prv_confirm_load(Window *w) { s_confirm_canvas = prv_add_canvas(w, prv_confirm_draw); }
static void prv_edit_load(Window *w) { s_edit_canvas = prv_add_canvas(w, prv_edit_draw); }
static void prv_edit_unload(Window *w) {
  layer_destroy(s_edit_canvas);
  s_edit_canvas = NULL;
}
static void prv_confirm_unload(Window *w) {
  layer_destroy(s_confirm_canvas);
  s_confirm_canvas = NULL;
}

#ifdef PUFFBLE_DEMO
// Emulator screenshots only: build with -DPUFFBLE_DEMO=<state> to show that screen with
// sample data and no phone. Never in release builds.
static void prv_demo(void) {
  static const char *const names[PROFILE_COUNT] = {"Ultra Flavor", "Balanced Flavor", "Daily", "Most Vapor"};
  static const uint16_t temps[PROFILE_COUNT] = {475, 500, 510, 570};
  static const uint32_t rgbs[PROFILE_COUNT] = {0xFF00D0, 0x53FF12, 0xFF005D, 0x6600FF};
  for (int i = 0; i < PROFILE_COUNT; i++) {
    snprintf(s_p_name[i], NAME_LEN, "%s", names[i]);
    s_p_target_f[i] = temps[i];
    s_p_duration_s[i] = 120;
    s_p_rgb[i] = rgbs[i];
  }
  s_have_table = true;
  s_have_msg = true;
  s_show_tip = false;
  s_state = PUFFBLE_DEMO;
  s_batt = 87;
  s_duration_s = 120;
  // Each screen on a different profile, so the screenshots show off the colors.
  int p = 0;
  switch (s_state) {
    case STATE_IDLE: p = 0; s_heater_f = 82; break;
    case STATE_PREHEAT: p = 1; s_heater_f = 412; break;
    case STATE_ACTIVE: p = 3; s_heater_f = 568; s_remain_s = 80; break;
    case STATE_FADE: p = 2; s_heater_f = 455; break;
    case STATE_COOLDOWN: p = 0; s_heater_f = 217; s_remain_s = 93; break;
  }
  s_profile = p;
  snprintf(s_name, NAME_LEN, "%s", names[p]);
  s_target_f = temps[p];
  s_ring_rgb = rgbs[p];
  s_remain_at_ms = prv_now_ms();
  s_temp_shown = s_heater_f;
}
#endif

static void prv_init(void) {
  s_settings = persist_exists(PERSIST_SETTINGS) ? persist_read_int(PERSIST_SETTINGS) : 0;
  if (persist_exists(PERSIST_CUSTOM_F)) s_custom_f = persist_read_int(PERSIST_CUSTOM_F);
  if (persist_exists(PERSIST_CUSTOM_S)) s_custom_s = persist_read_int(PERSIST_CUSTOM_S);
#ifdef PUFFBLE_DEMO
  prv_demo();
#endif
  app_message_register_inbox_received(prv_inbox_received);
  app_message_register_inbox_dropped(prv_inbox_dropped);
  app_message_register_outbox_sent(prv_outbox_sent);
  app_message_register_outbox_failed(prv_outbox_failed);
  app_message_open(512, 64);

  s_main_window = window_create();
  window_set_click_config_provider(s_main_window, prv_main_clicks);
  window_set_window_handlers(s_main_window, (WindowHandlers){.load = prv_main_load, .unload = prv_main_unload});

  s_confirm_window = window_create();
  window_set_click_config_provider(s_confirm_window, prv_confirm_clicks);
  window_set_window_handlers(s_confirm_window,
                             (WindowHandlers){.load = prv_confirm_load, .unload = prv_confirm_unload});

  s_edit_window = window_create();
  window_set_click_config_provider(s_edit_window, prv_edit_clicks);
  window_set_window_handlers(s_edit_window, (WindowHandlers){.load = prv_edit_load, .unload = prv_edit_unload});

  s_settings_window = window_create();
  window_set_window_handlers(s_settings_window,
                             (WindowHandlers){.load = prv_settings_load, .unload = prv_settings_unload});

  window_stack_push(s_main_window, true);
  tick_timer_service_subscribe(SECOND_UNIT, prv_tick);
  app_focus_service_subscribe(prv_focus);
  // Ask the phone for the current state; repeat until it answers.
  s_refresh_timer = app_timer_register(300, prv_refresh_timer, NULL);
  s_tip_timer = app_timer_register(TIP_SHOW_MS, prv_tip_timer, NULL);
  prv_apply_light(true);
  prv_anim_kick();
}

static void prv_deinit(void) {
  light_enable(false);
  app_focus_service_unsubscribe();
  tick_timer_service_unsubscribe();
  window_destroy(s_edit_window);
  window_destroy(s_settings_window);
  window_destroy(s_confirm_window);
  window_destroy(s_main_window);
}

int main(void) {
  prv_init();
  app_event_loop();
  prv_deinit();
}
