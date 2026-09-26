<div align="center">

# 🛠️ ApkBuild (Android-Code)

**Build APK Android langsung di perangkat** — tanpa Gradle, tanpa Android Studio.

Meniru alur **CodeAssist** (build system `JavaBuildSystem.kt`): satu aplikasi Android
yang membuat, mengedit, dan membangun proyek APK di perangkat itu sendiri.

</div>

---

## ✨ Fitur

- 📦 **Build APK tanpa Gradle** — toolchain build dijalankan di perangkat
- 🆕 **Buat proyek** — form nama, package, version, compile/min/target SDK, versi Java
- ✏️ **Editor kode** — [sora-editor](https://github.com/Rosemoe/sora-editor) + TextMate (syntax highlight Java/Kotlin/XML)
- ▶️ **Build sekali tekan** — konsol terintegrasi, timing tiap tahap, tab LOG / PROBLEM
- 📚 **Katalog library** — unduh & pilih dependensi Maven/AAR (meniru library picker CodeAssist)
- 🎨 **Tema** — Sunny Glow / Blue Wave / Material You, mode malam
- 📥 **Auto-install APK** setelah build sukses
- 🐍 **Dukungan Kotlin** — kompilasi `.kt` dengan compiler K2 di perangkat

---

## 🏗️ Arsitektur

```
app/src/main/java/com/ikram/apkbuild/
├── activity (host)
│   ├── MainActivity.java          Daftar proyek, unduh library
│   ├── CreateProjectActivity.java Form proyek baru + pilih library
│   ├── EditorActivity.java        Editor + Build + konsol + install
│   └── ModuleConfigActivity.java  Edit module.toml proyek
├── adapter/                       ProjectListAdapter, LibraryAdapter, FileTreeAdapter
├── build/                         🏭 Pipeline build
│   ├── BuildPipeline.java         Orkestrasi tahap + cache fingerprint
│   ├── StageRunner.java           Runner tahap linear + cache per tahap
│   ├── InProcessTools.java        ECJ / D8 / apksig via DexClassLoader
│   ├── ProcessRunner.java         Subprocess (aapt2)
│   ├── ResMerger.java             Gabung resource AAR
│   ├── ViewBindingGen.java        Generate <Layout>Binding
│   ├── RBytecode.java             R.java -> R.jar (bytecode)
│   ├── KotlinIncremental.java     Rencana compile Kotlin inkremental
│   ├── KotlinAbi.java             Snapshot ABI Kotlin (deteksi inline)
│   ├── KotlinMetadataStrip.java   Buang @kotlin.Metadata sebelum dex
│   └── TextMateLanguages.java     Setup grammar TextMate
├── deps/MavenResolver.java        Resolusi dependensi Maven/AAR
├── toolchain/
│   ├── ToolchainManager.java      Ekstrak & kelola aset toolchain
│   ├── JvmRunner.java             Fork dalvikvm (ECJ/D8/apksig)
│   └── KotlinForkWorker.java      Worker Kotlin persisten
├── LibraryCatalog.java            Katalog library
├── ProjectConfig.java             Baca/tulis module.toml
├── ProjectCreator.java            Scaffold proyek baru
├── Themes.java                    Palet + mode malam
├── FileIconManager.java           Ikon per jenis file
└── Util.java                      I/O, hash, log
```

---

## 🏭 Pipeline build

```
resource → compileKotlin → compileJava → dex → package → sign
```

| Tahap | Alat | Keluaran |
|-------|------|----------|
| `resource` | aapt2 (subprocess) | `resources.ap_`, `gen/R.java`, ViewBinding |
| `compileKotlin` | Kotlin K2 (worker persisten) | `kotlin-classes/*.class` |
| `compileJava` | ECJ 3.40 | `classes/*.class`, `R.jar` |
| `dex` | D8 8.13 (in-process / fork) | `dex/classes*.dex` |
| `package` | `java.util.zip` murni | `unsigned.apk` |
| `sign` | apksig | `output.apk` |

Setiap tahap punya **fingerprint** (`.stage-cache/<nama>.fp`); bila input + output
tak berubah, tahap **di-skip**.

### Dex: bucket-lite

```
classes/*.class  ─group hash─▶  N bucket (min(8, cores))
                                  │
              manifest berubah? ──┼─▶ archive per-class (dexcache/) ─▶ merge ─▶ g<b>.dex
                                  │
              manifest sama? ─────┴─▶ reuse g<b>.dex
                                  │
                                  └─▶ dexDir/classes.dex, classes2.dex, …
```

D8 `D8Entry` (dalam `r8.dex.zip`) punya dua mode tambahan:

```
--intermediate --per-class --globals-output <dir>   # archive: 1 class -> 1 .dex
--merge-globals <file...>                           # merge: dex + globals -> 1 dex
```

Hanya bucket yang berubah di-archive + di-merge. Globals synthetics dikumpulkan
per class dan dioper ke merge (meniru `DexGlobalSynthetics`).

### Fallback berlapis

Tiap alat punya jalur **in-process** (`DexClassLoader`) lalu **fork dalvikvm**:

| Alat | In-process | Fallback |
|------|-----------|----------|
| ECJ | `InProcessTools.ecj` | `JvmRunner.ecj` |
| D8 (app) | `D8Entry` via refleksi | `JvmRunner.d8WithClasspath` |
| D8 (archive) | `InProcessTools.d8Archive` | `JvmRunner.d8EntryArchive` |
| D8 (merge) | `d8Merge` | `JvmRunner.d8EntryMerge` |
| apksig | `InProcessTools.apksig` | `JvmRunner.apksig` |
| Kotlin | worker persisten | fork `dalvikvm` one-shot |

In-process lebih cepat (tak bayar start VM), tapi heap terbatas oleh
`dalvik.vm.heapsize` aplikasi. Fork punya `-Xmx` sendiri.

---

## 🧰 Toolchain

Dibundel sebagai **aset** di APK host, diekstrak sekali ke `filesDir/toolchain/libs/`:

| Aset | Isi |
|------|-----|
| `android.jar` | Platform SDK (API 36) |
| `ecj.dex.zip` | ECJ 3.40.0 (di-patch `Runtime$Version` + shim) |
| `r8.dex.zip` | D8/R8 8.13.19 + `D8Entry` |
| `apksig.dex.zip` | apksig 8.7.3 |
| `core-lambda-stubs.jar` | Stub desugar |
| `debug.keystore`, `debug.p12` | Kunci debug |
| `viewbinding/viewbinding.jar` | Interface `androidx.viewbinding.ViewBinding` |
| `kotlin/kotlin-compiler.dex.zip` | Compiler K2 2.4 (68 MB) |
| `kotlin/kotlin-stdlib.{jar,dex.zip}` | Kotlin stdlib |
| `kotlin/kotlin-reflect.dex.zip` | kotlin-reflect |
| `kotlin/kotlinc-worker.dex.zip` | Worker persisten |
| `textmate/` | Grammar syntax highlight |
| `JetBrainsMono-Regular.ttf` | Font editor |

**Native:** `libaapt2.so` di `jniLibs/` (arm64-v8a, armeabi-v7a, x86, x86_64) —
satu-satunya komponen non-Java, dijalankan sebagai subprocess.

**Dependensi host** (`app/module.toml`):

```toml
implementation = [
  "androidx.appcompat:appcompat:1.7.0",
  "com.google.android.material:material:1.12.0",
  "androidx.annotation:annotation:1.9.1",
  "org.ow2.asm:asm:9.7",
  "org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0",
  "io.github.rosemoe:editor:0.24.6",
  "io.github.rosemoe:language-java:0.24.6",
  "io.github.rosemoe:language-textmate:0.24.6",
  "androidx.databinding:viewbinding:8.7.3",
]
```

---

## 📄 Format proyek pengguna

Proyek yang dibangun ApkBuild **tanpa Gradle** — hanya `module.toml`:

```
proyek/
├── app/
│   ├── module.toml              # konfigurasi modul
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/<pkg>/…         # sumber Java
│       ├── kotlin/<pkg>/…       # sumber Kotlin (opsional)
│       └── res/…                # resource
└── app/build/output.apk         # hasil build
```

`module.toml`:

```toml
version = 2

[module]
type = "android-app"
name = "app"
languageLevel = "JAVA_21"
output = "build/classes"

[sourceSets.main]
scope = "IMPLEMENTATION"
java   = ["src/main/java"]
res    = ["src/main/res"]

[android]
namespace   = "com.example.app"
compileSdk  = 36
minSdk      = 26
targetSdk   = 36
manifest    = "src/main/AndroidManifest.xml"
versionCode = 1
versionName = "1.0"
isApplication = true

[build]
dexMode           = "auto"        # auto | full | incremental
parallelDex       = "false"
javaExec          = "inprocess"   # inprocess | fork
kotlinExec        = "forkworker"  # inprocess | forkworker
kotlinWorkerHeapMb = 0            # 0 = otomatis
dexMergeForks     = 0             # 0 = otomatis

[dependencies]
implementation = ["androidx.appcompat:appcompat:1.7.0"]
```

| Kunci `[build]` | Fungsi |
|---|---|
| `dexMode` | `auto` (per-class bila cache ada), `full`, `incremental` |
| `parallelDex` | Merge bucket serentak via fork |
| `javaExec` | `inprocess` = ECJ di dalam app; `fork` = dalvikvm terpisah |
| `kotlinExec` | `forkworker` = VM persisten (heap sendiri, environment hangat) |
| `kotlinWorkerHeapMb` | Heap worker Kotlin; `0` = ladder otomatis |
| `dexMergeForks` | Jumlah worker merge paralel; `0` = otomatis |

---

## 🧠 Catatan implementasi

Hal-hal yang **tidak jelas dari kode** dan penting untuk pengembangan lanjutan.

### Kotlin dikompilasi SEBELUM Java

Classpath Kotlin hanya `R.jar` + library — **bukan** output Java. Karena itu
**Kotlin tidak boleh merujuk kelas Java**. Bila perlu nilai dari kelas Java,
duplikasi logikanya di Kotlin atau lewatkan sebagai parameter.

### Metadata Kotlin harus dibuang sebelum dex

Compiler K2 2.4 menulis `@kotlin.Metadata` versi 2.4; R8/D8 bawaan (8.13) hanya
paham sampai 2.3. Saat gagal me-rewrite, D8 **melempar internal, mencetak WARNING
saja, mengembalikan "sukses", lalu menjatuhkan seluruh output dex invocation itu** —
class Kotlin hilang dari APK tanpa pesan error.

`KotlinMetadataStrip` membuang anotasi itu (ala CodeAssist
`DexArchives.strippedKotlinMetadata`) sehingga D8 tak pernah masuk jalur metadata.

### Kotlin stdlib harus tunggal

Dua `kotlin-stdlib` beda versi di classpath membuat D8 berhenti dengan
`Classpath type already present: kotlin/jvm/functions/Function1`. Dedup per-tipe
**tidak menolong** — tiap versi punya class unik sendiri, jadi dua-duanya lolos.

Aturan: stdlib proyek menang; stdlib aset toolchain **tidak** ditambahkan bila
proyek sudah punya. `dedupKotlinStdlib()` menjadi jaring terakhir sebelum D8.

> Pengecualian: jalur **runtime compiler** (`ktWorker`, `inprocess.kotlinc`) tetap
> memakai stdlib **aset** — itu milik compiler, bukan stdlib program.

### `gen/R.java` hanya dibuang bila resource berubah

Build gagal dulu **selalu** menghapus `gen/`, memaksa `aapt2 link` (~10 s) di setiap
build setelah kegagalan. Sekarang `.gen-fp` mencatat fingerprint resource saat
`R.java` ditulis, dan `gen/` hanya dibuang bila fingerprint berbeda.

Aman karena syaratnya **identik** dengan jalur `link reuse` (`fpRes == .resap-fp`).

### Android 14+/SELinux: `pm install` dari path app gagal

`system_server` hanya boleh membaca file berkonteks `shell_data_file`/tmpfs:
- `/storage/emulated/0` → FUSE → ditolak
- `getCacheDir()` / `getFilesDir()` → `app_data_file` → ditolak

Jalur yang bekerja: `/data/local/tmp` (mode 0644), atau `pm install -S` dengan APK
dialirkan lewat **stdin** (tak menyentuh path sama sekali).

### Build gagal membuang artefak setengah jadi

Saat gagal: `output.apk`, `.fingerprint`, `classes/`, `.stage-cache`, `R.jar`,
`.rjar-fp` dibuang. **Sengaja dipertahankan:** `flat/user.zip`, `.userfp`,
`resources.ap_`, `.resap-fp` (hasil tahap `resource` yang sudah selesai).

### Cache di-key by konten, bukan `mtime`

Di storage FUSE/ART, `mtime` bisa tak naik saat file ditimpa — dex lama dipakai
untuk kelas baru → class hantu → FC. Semua fingerprint memakai hash konten.

---

## 🚀 Build

**Host (ApkBuild sendiri)** dibangun memakai **CodeAssist**, dengan `module.toml`
yang sama. Aset toolchain (kecuali `libaapt2.so`) berasal dari repo CodeAssist.

**Untuk membangun proyek pengguna:** buka ApkBuild → tap proyek → tekan **Build**.

---

## 📋 Kendala yang diketahui

- **Heap in-process terbatas.** Proses app dibatasi `dalvik.vm.heapsize`
  (~512 MB–1 GB) berapa pun RAM fisik. Tahap berat (D8 archive) memakai fallback
  fork agar dapat `-Xmx` sendiri.
- **`aapt2` butuh ABI cocok.** Bila `libaapt2.so` tak sesuai arsitektur, tahap
  `resource` gagal. ToolchainManager menyediakan `aapt2()` yang mencari `libaapt2.so` di `nativeLibraryDir`, lalu fallback ke hasil ekstrak manual.
- **Build pertama lambat** (~70–80 s) untuk proyek dengan library banyak; build
  berikutnya jauh lebih cepat karena cache tahap + bucket dex.

---

## 🙏 Kredit

Terinspirasi & memakai komponen dari **[CodeAssist](https://github.com/tyron12233/CodeAssist)**
(GNU GPL v3) oleh tyron12233 — build system Gradle-free, aset toolchain, dan pola
in-process D8/ECJ.

---

## 📄 Lisensi

Proyek ini meniru dan memakai komponen CodeAssist yang berlisensi **GNU GPL v3**.
Distribusi ulang wajib mengikuti ketentuan GPL v3.
