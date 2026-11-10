# Masukan Perubahan untuk CoolMax

Dokumen ini berisi hasil logging/pengujian runtime aplikasi **CoolMax** (`id.coolmax.app.debug`) dan rekomendasi perubahan agar fitur **Profile When Screen Off** serta penggantian profil CPU bekerja dengan benar.

---

## 1. Ringkasan

Status fitur **Profile When Screen Off: TIDAK BEKERJA EFEKTIF** di perangkat uji.

- Trigger layar mati **berhasil** (broadcast `SCREEN_OFF` diterima, foreground service aktif).
- Aksi penggantian profil **gagal / tidak pernah benar-benar diterapkan** karena beberapa sebab:
  1. Mode **Dry-Run aktif**.
  2. **Thermal guard 45 °C** memblokir hampir semua apply.
  3. Penulisan **`scaling_max_freq` diabaikan/di-clamp** oleh driver cpufreq MediaTek.
  4. **SELinux** memblokir pembacaan `/sys/class/thermal`.
  5. **Screen-On Restore** mengembalikan snapshot lama (`schedutil`), sehingga governor terlihat "stuck".

---

## 2. Lingkungan Pengujian

| Item | Nilai |
|---|---|
| Perangkat | Redmi Note 10 (M2101K7BNY) |
| SoC | MediaTek MT6785V/CD (Helio G95) |
| Android | 16 |
| Kernel | 4.19.325 |
| Root | KernelSU (app ada di allowlist root) |
| Package | `id.coolmax.app.debug` |
| Cluster CPU | `policy0` = cpu0–5, `policy6` = cpu6–7 |
| Governor tersedia | `ondemand userspace powersave performance schedutil` |

Metode pengumpulan data:

- `logcat` (event broadcast, foreground service, audit SELinux).
- Event log internal app pada `files/datastore/coolmax_prefs.preferences_pb`.
- Tes tulis/baca langsung ke sysfs cpufreq sebagai root.
- Inspeksi string/kode dari APK (`classes*.dex`, `resources.arsc`).

---

## 3. Temuan

### 3.1 Dry-Run aktif

Nilai preferensi:

```text
dry_run_mode = true
```

Semua event terbaru hanya berupa simulasi:

```text
[DRY-RUN] Simulated 'Screen-Off (BATTERY_SAVER)' via Screen-Off Profile (13 commands).
[DRY-RUN] Simulated 'Screen-On Restore' via Screen-Off Profile (13 commands).
```

Artinya tidak ada perintah yang benar-benar ditulis ke sysfs. Ini alasan paling langsung kenapa profil CPU tidak berubah.

**Rekomendasi**

- Dry-Run tidak boleh aktif secara diam-diam setelah sebelumnya berhasil apply.
- Tampilkan badge yang jelas dan persisten di semua tab ketika Dry-Run aktif.
- Saat pengguna memilih profil, tampilkan dialog konfirmasi: "Dry-Run aktif, perubahan tidak akan diterapkan. Matikan Dry-Run?".
- Jangan aktifkan Dry-Run otomatis kecuali root tidak tersedia atau Safe Mode aktif.

### 3.2 Thermal guard 45 °C terlalu rendah

Event log (100 event terakhir): **94 event `HIGH_TEMPERATURE`**, 6 event `SCREEN_OFF`.

Contoh:

```text
Blocked profile 'System Default Snapshot' because CPU temperature (59.2°C)
exceeds thermal safety limit (45.0°C).
```

Suhu CPU normal perangkat ini 50–75 °C, sehingga batas 45 °C membuat aplikasi **hampir selalu menolak** apply profil, termasuk perubahan manual.

Nilai 45 °C terlihat **hardcoded** (tidak ada key konfigurasi di datastore).

**Rekomendasi**

- Jadikan batas suhu aman **dapat dikonfigurasi** (slider), dengan default yang realistis per perangkat (mis. 65–70 °C untuk kelas SoC ini).
- Bedakan batas untuk:
  - profil **menurunkan** frekuensi (tidak perlu diblokir karena panas), dan
  - profil **menaikkan** frekuensi/performance (baru perlu diblokir saat panas).
- Bug logika: saat `HIGH_TEMPERATURE`, app mencoba apply "System Default Snapshot" tetapi **diblokir oleh guard yang sama**. Fallback pendinginan harus selalu diizinkan (whitelist), jangan kena guard.
- Kurangi spam: 94 event blokir berulang tiap ±11 detik. Deduplikasi/rate-limit, dan cukup satu event sampai suhu turun.

### 3.3 `scaling_max_freq` diabaikan driver MediaTek

Tes langsung sebagai root:

```text
sebelum      = 2000000
write 1075000 (exit 0)
baca lagi    = 2000000
write 1500000 (exit 0)
baca lagi    = 2000000
```

- `scaling_governor` **bisa** ditulis dan bertahan (mis. `performance`).
- `scaling_min_freq` **bisa** ditulis.
- `scaling_max_freq` **tidak bertahan** (di-clamp oleh PPM/thermal driver MTK).

Ini juga penyebab event apply nyata gagal verifikasi:

```text
Verification failed on .../policy0/scaling_max_freq:
expected '1075000', actual '2000000'
(possibly overridden by thermal engine or vendor power HAL)
```

**Rekomendasi**

- Deteksi SoC/vendor. Untuk MediaTek, jangan hanya mengandalkan `scaling_max_freq`.
- Gunakan interface MTK yang benar, misalnya:
  - `/proc/cpufreq/MT_CPU_DVFS_LL/cpufreq_oppidx` (cluster cpu0–5)
  - `/proc/cpufreq/MT_CPU_DVFS_L/cpufreq_oppidx` (cluster cpu6–7)
  - atau interface `cpufreq_limited_*` bila tersedia.
- Petakan `maxFreqKhz` ke indeks OPP terdekat, lalu tulis indeks tersebut.
- Sediakan mode fallback: kalau `scaling_max_freq` tidak berubah setelah write, otomatis coba interface MTK dan laporkan metode yang dipakai.

### 3.4 SELinux memblokir pembacaan suhu

`logcat` berulang:

```text
avc: denied { search } for name="thermal" ... tcontext=u:object_r:sysfs_therm:s0
scontext=u:r:untrusted_app:s0 ... app=id.coolmax.app.debug
```

Akses langsung app ke `/sys/class/thermal` ditolak. Pembacaan suhu harus lewat jalur yang diizinkan.

**Rekomendasi**

- Baca suhu via root shell (`su -c cat ...`) karena app sudah punya akses root, atau
- Gunakan `PowerManager`/`ThermalService` (`getCurrentThermalStatus`) sebagai fallback, atau
- Sediakan daftar path thermal zone dan pilih yang bisa dibaca; jangan spam percobaan ke path yang di-deny SELinux.
- Tampilkan status "sensor tidak tersedia" bila semua jalur gagal (string sudah ada: `/sys/class/thermal sensors unavailable`).

### 3.5 Screen-On Restore selalu kembali ke snapshot lama

Event "Screen-On Restore" menulis:

```text
echo 'schedutil' > .../policy0/scaling_governor
echo 'schedutil' > .../policy6/scaling_governor
```

Snapshot "Pre-Screen-Off" yang dipakai berisi `schedutil`, jadi setelah layar menyala governor memang kembali `schedutil`. Ini membuat pengguna merasa "stuck di schedutil" meski screen-off sempat bekerja.

**Rekomendasi**

- Perjelas di UI bahwa Screen-On Restore mengembalikan kondisi **sebelum** layar mati, bukan menerapkan `screen_on_config_json`.
- Jika pengguna punya `screen_on_config_json` (mis. `performance`), tawarkan opsi: "Restore snapshot sebelumnya" vs "Selalu pakai konfigurasi Screen-On".
- Pastikan snapshot diambil pada kondisi yang benar (jangan saat governor sedang dipaksa `performance`).

### 3.6 Label trigger event salah

Event restore layar nyala tercatat dengan trigger `SCREEN_OFF`:

```text
[14:13:42] SCREEN_OFF | Screen-On Restore | success=True
```

**Rekomendasi**

- Tambahkan trigger `SCREEN_ON` tersendiri, atau minimal bedakan `action` (apply/restore) dari `trigger`.
- Ini penting agar log mudah dianalisis dan tidak menyesatkan.

### 3.7 Urutan perintah berpotensi menyebabkan clamp

Saat ini urutan penulisan: `scaling_min_freq` → `scaling_max_freq` → `scaling_governor`.

Jika governor sebelumnya `performance` (min = max = frekuensi tertinggi), penulisan `scaling_max_freq` bisa langsung di-clamp balik sebelum governor diganti.

**Rekomendasi**

- Urutan aman:
  1. Set governor netral dulu (`schedutil`/`userspace`), atau
  2. Set `scaling_max_freq` **setelah** governor target terpasang, atau
  3. Untuk MTK gunakan `cpufreq_oppidx`.
- Verifikasi ulang `governor`, `min_freq`, dan `max_freq` setelah semua perintah, bukan hanya sebagian.

### 3.8 Verifikasi belum lengkap

Event gagal hanya melaporkan `scaling_max_freq`, tidak melaporkan status `scaling_governor`/`scaling_min_freq`.

**Rekomendasi**

- Verifikasi semua nilai yang diubah dan tampilkan ringkasan:
  - `governor: expected=schedutil actual=performance`
  - `max_freq: expected=1075000 actual=2000000`
- Simpan hasil verifikasi ke event log agar mudah dilaporkan pengguna.

---

## 4. Prioritas Perubahan

| Prioritas | Perubahan |
|---|---|
| P0 | Buat batas suhu aman dapat dikonfigurasi; naikkan default; jangan blokir fallback pendinginan |
| P0 | Perbaiki penanganan MediaTek: gunakan `cpufreq_oppidx` / `cpufreq_limited_*`, bukan hanya `scaling_max_freq` |
| P0 | Cegah Dry-Run aktif tanpa sepengetahuan pengguna; beri badge + konfirmasi |
| P1 | Pisahkan trigger `SCREEN_ON` dan `SCREEN_OFF`; perbaiki label event |
| P1 | Rate-limit/deduplikasi event `HIGH_TEMPERATURE` |
| P1 | Baca suhu lewat root shell atau `ThermalService` agar tidak kena SELinux |
| P2 | Perbaiki urutan perintah apply dan verifikasi seluruh nilai |
| P2 | Opsi restore snapshot vs konfigurasi Screen-On |

---

## 5. Saran Implementasi (Kotlin, konseptual)

### 5.1 Deteksi dukungan `scaling_max_freq`

```kotlin
fun writeAndVerify(path: File, value: String): Boolean {
    return try {
        path.writeText(value)
        path.readText().trim() == value.trim()
    } catch (t: Throwable) {
        false
    }
}
```

Jika `false`, fallback ke interface MTK.

### 5.2 Fallback OPP MediaTek

```kotlin
data class Opp(val index: Int, val freqKhz: Int)

fun limitMaxFreqMtk(cluster: MtkCluster, maxKhz: Int): Boolean {
    val opps = readOppTable(cluster)          // /proc/cpufreq/MT_CPU_DVFS_*/cpufreq_oppidx
    val target = opps.firstOrNull { it.freqKhz <= maxKhz } ?: return false
    val node = File("/proc/cpufreq/${cluster.nodeName}/cpufreq_oppidx")
    return writeAndVerify(node, target.index.toString())
}
```

### 5.3 Thermal guard yang masuk akal

```kotlin
val isCoolingProfile = target.maxFreqKhz <= current.maxFreqKhz
val allowed = isCoolingProfile || cpuTempC <= thermalLimitCelsius

if (!allowed) {
    logOncePerState(
        "HIGH_TEMPERATURE",
        "Blocked '${target.name}' because CPU temperature (${cpuTempC}°C) " +
        "exceeds thermal safety limit (${thermalLimitCelsius}°C)."
    )
    return Rejected
}
```

---

## 6. Kriteria Sukses

- Dengan Dry-Run OFF dan suhu di bawah batas, apply profil mengubah `scaling_governor` dan `scaling_min_freq` (dan `max_freq`/`oppidx` di MTK) lalu lolos verifikasi.
- Event log mencatat `SCREEN_OFF` (apply) dan `SCREEN_ON` (restore) secara terpisah.
- Tidak ada lagi blokir `HIGH_TEMPERATURE` berulang saat suhu normal perangkat (50–60 °C).
- Tidak ada `avc denied` berulang untuk `/sys/class/thermal`.
- Setelah layar nyala, governor sesuai kebijakan yang dipilih pengguna (snapshot atau konfigurasi Screen-On).

---

## 7. Lampiran: Contoh Event Log

```text
[14:13:42] SCREEN_OFF | Screen-On Restore | success=True
    [DRY-RUN] Simulated 'Screen-On Restore' via Screen-Off Profile (13 commands).
[14:13:35] HIGH_TEMPERATURE | System Default Snapshot | success=False
    Blocked profile 'System Default Snapshot' because CPU temperature (53.4°C)
    exceeds thermal safety limit (45.0°C).
[14:12:22] SCREEN_OFF | Screen-Off (BATTERY_SAVER) | success=True
    [DRY-RUN] Simulated 'Screen-Off (BATTERY_SAVER)' via Screen-Off Profile (13 commands).
[13:58:43] SCREEN_OFF | Screen-Off (BATTERY_SAVER) | success=False
    Verification failed on .../policy0/scaling_max_freq: expected '1075000',
    actual '2000000' (possibly overridden by thermal engine or vendor power HAL);
    Verification failed on .../policy6/scaling_max_freq: expected '1085000',
    actual '2050000' ...
```

Preferensi terkait:

```json
{
  "dry_run_mode": true,
  "screen_off_settings_json": {
    "enabled": true,
    "preset": "BATTERY_SAVER",
    "delaySeconds": 5,
    "skipWhenCharging": true,
    "skipWhenMediaPlaying": true
  }
}
```
