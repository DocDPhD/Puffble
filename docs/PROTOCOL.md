# Puffco Proxy Bluetooth protocol (as Puffble uses it)

Built from the public [PuffcoBLE](https://github.com/Fr0st3h/PuffcoBLE) and
[melt-linux](https://github.com/jen88uk/melt-linux) projects, then checked against a real
Proxy (firmware API level 7). Where those projects disagree, this file says what the device
actually did.

## Bonding first

The command characteristic needs an encrypted, bonded link. Without a bond the Proxy silently
drops every command (a write-with-response fails with ATT error 0x0F, Insufficient
Authentication). The Proxy only accepts a new bond while in pairing mode.

## GATT

Service `e276967f-ea8a-478a-a92e-d78f5dd15dd5`:

| Characteristic | Use |
|---|---|
| `05434bca-cc7f-4ef6-bbb3-b1c520b9800c` | Version (read) |
| `60133d5c-5727-4f2c-9697-d842c5292a3c` | Command (write / write without response) |
| `8dc5ec05-8f7d-45ad-99db-3fbde65dbd9c` | Reply (notify) |
| `43312cd1-7d34-46ce-a7d3-0a98fd9b4cb8` | Events (notify, unused by Puffble) |

Request MTU 247; replies carry up to 125 value bytes.

## Framing

```
request  [seq u16 LE] [opcode u8] [body]
reply    [seq u16 LE] [status u8] [payload]     status 0 = success
```

| Opcode | Body |
|---|---|
| `0x00` GET_ACCESS_SEED | none; reply is a 16-byte seed |
| `0x01` UNLOCK_ACCESS | 16-byte key |
| `0x10` READ_SHORT | `offset u16, size u16, path` |
| `0x11` WRITE_SHORT | `offset u16, flags u8, path, 0x00, data` (flags 0 and 4 both accepted) |

Unlock: `key = SHA-256(handshake_key ‖ seed)[0:16]`, using the Lorax handshake key published in
both projects above.

## Values

| Path | Type |
|---|---|
| `/p/app/stat/id` | u8 state: 5 idle, 6 temp select, 7 preheat, 8 active, 9 fade |
| `/p/app/hcs` | u8 selected profile 0–3 (read/write) |
| `/p/app/mc` | u8 mode command (write): 7 start, 8 abort, 9 boost |
| `/p/app/thc/temp`, `/u/app/hc/{n}/temp` | **u32, tenths of °C** (not float32) |
| `/p/app/thc/time`, `/u/app/hc/{n}/time` | **u32, 1/200 s** |
| `/p/app/htr/temp` | u32, tenths of °C (live heater) |
| `/p/bat/soc` | u32, hundredths of a percent |
| `/u/app/hc/{n}/name` | text |
| `/u/app/hc/{n}/colr` | CBOR, chunked read (see below) |

`/p/app/hcp` (used by melt-linux) returns nothing on this firmware; `/p/app/hcs` is the selected profile.

## Ring color (`colr`)

A CBOR map with **integer keys**. `map[0][1][6]` is a byte string of RGB triples (the light
animation's colors); `map[1][9]`, when present, is the short list the official app shows.
Puffble uses the first color of `map[1][9]`, falling back to `map[0][1][6]`.
