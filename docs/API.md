# Spesifikasi API Lokal (Android ↔ PC Monitor)

Semua komunikasi terjadi di jaringan lokal (LAN/WiFi) yang sama, tidak
keluar internet.

## 1. HTTP Status Endpoint

- **Host**: IP HP di jaringan lokal
- **Port default**: `8080`
- **Endpoint**: `GET /status`
- **Response**: `application/json`

```json
{
  "connected": true,
  "youtubeConnected": true,
  "resolution": "1280x720",
  "fps": 30,
  "bitrateKbps": 2200,
  "droppedFrames": 3,
  "uptimeSec": 754,
  "timestamp": 1755590000
}
```

| Field | Tipe | Keterangan |
|---|---|---|
| `connected` | boolean | Capture card UVC terdeteksi & aktif |
| `youtubeConnected` | boolean | Koneksi RTMP ke YouTube sedang tersambung |
| `resolution` | string | Resolusi capture aktif |
| `fps` | number | Frame per second aktif |
| `bitrateKbps` | number | Bitrate video terkini (kbps) |
| `droppedFrames` | number | Total frame drop sejak stream dimulai |
| `uptimeSec` | number | Lama stream berjalan (detik) |
| `timestamp` | number | Unix timestamp saat data diambil |

PC Monitor melakukan **polling** endpoint ini tiap 1 detik. Tidak ada
endpoint kontrol (start/stop) di versi ini — murni monitoring, sesuai
prinsip *non-intrusive* di `ARCHITECTURE.md`.

## 2. UDP Discovery

- **Port**: `8888`
- **Arah**: HP → broadcast ke seluruh jaringan lokal
- **Interval**: setiap 2 detik
- **Format pesan** (plain text, dipisah `|`):

```
UFC_MONITOR|<ip_hp>|<http_port>|<nama_device>
```

Contoh:
```
UFC_MONITOR|192.168.1.23|8080|Redmi A3
```

PC Monitor mendengarkan broadcast ini; begitu ada pesan valid, ambil
`<ip_hp>` + `<http_port>` untuk mulai polling `/status`.

> Catatan: pendekatan ini dipilih karena simpel dan tidak butuh dependency
> tambahan. Bisa di-upgrade ke mDNS/Bonjour (`NsdManager` di Android) kalau
> nanti butuh discovery yang lebih robust di jaringan dengan banyak device.
