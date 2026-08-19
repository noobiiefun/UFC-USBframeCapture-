# Roadmap

- [ ] **1. Setup project & permission UVC** — project Android Studio jalan,
      capture card terdeteksi, preview tampil di layar.
- [ ] **2. Tuning capture & encoder** — resolusi/bitrate 720p30 stabil di
      Redmi A3 tanpa lag berat.
- [ ] **3. Integrasi RTMP push ke YouTube** — stream dari HP muncul live
      di YouTube Studio.
- [ ] **4. Local status server di Android** — `GET /status` bisa diakses
      dari browser/PC di jaringan yang sama.
- [ ] **5. PC monitor app (.exe)** — auto-discover HP, tampilkan dashboard
      status real-time.
- [ ] **6. Uji stabilitas end-to-end** — live beberapa jam, cek suhu HP,
      baterai, drop koneksi; tuning ulang bila perlu.

## Ide lanjutan (belum prioritas)

- Upgrade discovery UDP → mDNS/NSD
- UI pemilihan resolusi/bitrate dari dalam app (bukan hardcode)
- Riwayat/log sesi live streaming (durasi, rata-rata bitrate, drop total)
- Notifikasi PC Monitor kalau koneksi YouTube putus
- Auto-reconnect RTMP kalau koneksi sempat putus
