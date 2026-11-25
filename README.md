# RTTS (Android)

Real Time Translate Screen (RTTS)

Aplikasi terjemahan teks di layar HP. Bulatan mengambang di pinggir layar, tap untuk menyalakan atau mematikan terjemahan. Teks di layar dikenali di HP (ML Kit), lalu diterjemahkan langsung di HP lewat ML Kit. Model bahasanya diunduh sekali, setelah itu bisa dipakai tanpa internet.

Dukung pengembang: https://saweria.co/RizkyAhmad9

**Status:** kode ini belum pernah dikompilasi atau diuji di HP. Langkah build di bawah baru akan menunjukkan apakah ada error.

## 1. Siapkan Google Cloud Translation

1. Buka console.cloud.google.com, lalu buat project baru.
2. Aktifkan billing (wajib, meskipun ada kuota gratis).
3. Aktifkan **Cloud Translation API**.
4. Buat **API key** di menu Credentials.
5. Batasi key itu hanya untuk Cloud Translation API.

Jangan pernah menaruh API key di kode yang publik. Key cukup dimasukkan lewat layar pengaturan aplikasi.

## 2. Build APK lewat GitHub

1. Buat repository baru di GitHub (sebaiknya private).
2. Upload semua file di folder ini, termasuk folder `.github/workflows/`.
3. Buka tab **Actions**, lalu jalankan workflow **Build APK**.
4. Setelah selesai, unduh artifact `translate-layar-apk`, lalu install APK-nya di HP.
5. Jika diminta, izinkan instalasi dari sumber tidak dikenal.

Catatan: upload lewat browser HP membuat struktur folder hilang. Cara yang lebih rapi adalah lewat Termux (`unzip`, lalu `git push`).

## 3. Cara pakai

1. Buka aplikasi, pilih bahasa asal dan tujuan, lalu isi API key dan tekan **Simpan pengaturan**.
2. Tekan **1** untuk memberi izin "tampil di atas aplikasi lain".
3. Tekan **2**, lalu setujui izin rekam layar.
4. Bulatan muncul di pinggir layar. Tap untuk menyalakan terjemahan, tap lagi untuk mematikan.
5. Geser bulatan kalau menutupi teks. Untuk berhenti, tekan **Hentikan** di notifikasi.

## Batasan saat ini

- Bahasa Arab, Thai, dan Cyrillic belum didukung, karena ML Kit belum mendukung huruf-huruf itu.
- Hasil terjemahan berkedip sebentar setiap siklus, karena layar perlu disembunyikan sejenak saat diambil.
- Rotasi layar belum ditangani. Kalau layar diputar, perekaman perlu dimulai ulang.
- Vivo (Funtouch OS) kadang mematikan layanan latar. Matikan pembatasan baterai untuk aplikasi ini dan aktifkan autostart.
- Di aplikasi yang memblokir perekaman layar (misalnya banking), teks tidak akan terbaca.
- Teks di layar dikirim ke server Google untuk diterjemahkan. Jangan dipakai saat membuka password atau data rekening.
