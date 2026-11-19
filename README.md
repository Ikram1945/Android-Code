# ApkBuild — pembuat APK (meniru alur android-code-2)

Aplikasi Android (host) untuk membuat & membangun APK proyek user di perangkat.
Dibuild memakai CodeAssist. Toolchain build dibundle dari aset CodeAssist.

## Alur UI (meniru android-code-2)
1. **Home (MainActivity)**: daftar proyek (folder di /sdcard/Ikram yang punya
   app/module.toml). Tombol "+ Create" di atas. Tap proyek = buka editor.
   Long-tap = buka pengaturan.
2. **CreateProjectActivity**: form nama, package, versionName, versionCode,
   compileSdk, minSdk, targetSdk, versi Java (17/21). Setelah buat -> otomatis
   buka EditorActivity.
3. **EditorActivity**: daftar file (daftar file di app/src/main), tombol
   "Build", konsol di bawah, tombol "Salin Log", tombol "Pengaturan".
4. **ModuleConfigActivity**: edit module.toml (package APK, version, compile/
   min/target sdk, versi java).

## Format proyek user
```
proyek/
  app/
    module.toml
    src/main/java/<pkg>/...java
    src/main/res/...
    src/main/AndroidManifest.xml
```
Output build -> proyek/app/build/output.apk

## Build pipeline (BuildPipeline.java)
- aapt2(res) -> ECJ(compile) -> jar -> D8(dex) -> package -> apksig(sign)
- SETIAP tahap di-timing: log "[time] <tahap>: X ms" utk lihat bottleneck/error.
- ViewBinding: generate <Layout>Binding.java dari res/layout (pakai ViewBindingGen),
  interface androidx.viewbinding.ViewBinding dari assets/viewbinding (tanpa unduh).
- Tool ECJ/D8/apksig jalan via fork dalvikvm; aapt2 subprocess; apksig sign.

## Aset toolchain (assets/)
- android.jar (CodeAssist), core-lambda-stubs.jar, debug.keystore
- ecj.dex.zip (ECJ 3.40.0 di-patch Runtime$Version->shim + shim)
- r8.dex.zip (D8 8.13.19), apksig.dex.zip (apksig 8.7.3)
- viewbinding/ViewBinding.java (interface ViewBinding)
- libaapt2.so di jniLibs (aapt2 ReVanced v1.1.0)

## Kelas utama
- activity: MainActivity (home), CreateProjectActivity, EditorActivity,
  ModuleConfigActivity
- adapter: ProjectListAdapter
- build: BuildPipeline, ProcessRunner, ViewBindingGen
- deps: MavenResolver
- toolchain: ToolchainManager, JvmRunner
- Util, ProjectConfig, ProjectCreator

## Verifikasi
- Backend teruji: source contoh2 di-compile ECJ (-17) + jar + D8 dex sukses.
- Kode host compile ECJ tanpa error (dengan R dummy).
- aapt2/apksig/dalvikvm butuh uji di perangkat (jalankan via CodeAssist).

## Mode Dex & Library (meniru android-code-2)
module.toml punya:
```
[build]
dexMode = "auto"        # auto | full | incremental

[dependencies]
implementation = [ "androidx.appcompat:appcompat:1.7.0", "com.google.android.material:material:1.12.0" ]
```
- auto  : build pertama (belum ada cache) dex FULL app; build berikutnya
          (cache ada) dex per-class (hanya class/bytecode berubah).
- full  : selalu dex semua app class.
- incremental : selalu per-class.
- Library (Maven, .aar/.jar) di-resolve & di-dex-cache per konten
  (cacheDir/libdex/lib/<hash>/classes.dex) -> tidak di-dex ulang antar build.
- MERGE LIBRARY GLOBAL: semua library di-dex SATU KALI jadi satu dex gabungan
  (cacheDir/libdex/merged/<key>/classes.dex; key = hash gabungan tiap jar, urutan
  deklarasi). Set library sama -> berbagi cache lintas build & proyek -> build berikut
  cukup salin 1 file, bukan N; APK pun lebih sedikit dex. Fallback otomatis: bila merge
  global gagal/sistem (satu panggilan D8 besar), salin N dex per-library (perilaku lama).
- ECJ compile hanya file .java berubah (hash per-file di build/source-hash.txt).
- D8 app dex: desugaring classpath lengkap (android.jar + core-stubs + library).
- Fingerprint: jika sumber+library tak berubah -> skip build (APK reuse).

## Bucket-lite dex (incremental per-class + bucket merge)

Meniru inti CodeAssist (D8InProcessDexer + DexMergeTask) tanpa kompleksitas penuh.
D8Entry (r8.dex.zip, classes3.dex) kini dukung 2 mode tambahan:

```
--intermediate --per-class --globals-output <dir>   # archive: 1 class -> 1 .dex (+.globals)
--merge-globals <file...>                           # merge:    dex + globals -> 1 final dex
```

Alur app-dex:
1. classes/*.class di-group ke N bucket (N = min(8, cores), hash rel-path stabil).
2. Bucket yang manifest-nya berubah / dex-nya hilang -> re-archive class yang
   class-nya lebih baru dari cache (work/dexcache/<pkg>/C.dex) -> re-merge bucket
   -> work/buckets/g<b>.dex -> salin ke dexDir/classes.dex, classes2.dex, ...
3. Bucket tak berubah di-reuse (skip archive + skip merge). Hasil: build incremental
   hanya dex class berubah + merge bucket terkait, bukan full dex semua class.

Fallback: bila D8 in-process (mode archive/merge) tak tersedia atau gagal sistem,
otomatis full dex (perilaku lama). Fork dalvikvm tetap dipertahankan. Globals
synthetics (.globals) dikumpulkan per class dan di-pass ke merge lewat
--merge-globals (meniru DexGlobalSynthetics).

## Rancangan (belum diimplementasi): peningkatan bucket-lite

Tiga bagian CodeAssist yang sengaja ditinggalkan agar v1 sederhana. Ini hanya
rancangan tertulis, bukan kode. Implementasi menyusul setelah device-test v1 lolos.

### 1. Parallel merge (bucket di-merge serentak)

Sekarang: merge bucket berjalan SEQUENTIAL (satu per satu di thread utama).

Rancangan:
- `numBuckets = min(8, availableProcessors())` sudah ada. Sisanya: jalankan
  tiap bucket yang perlu re-merge lewat `Executors.newFixedThreadPool(koncurrency)`
  + `D8.run(command, executor)` (API D8 sudah dukung executor eksternal).
- `koncurrency = min(numBuckets, cores)`; thread-per-merge = `(cores/koncurrency).coerceIn(1,4)`.
- Heap gate: merge adalah puncak memori D8. Tambah semaphore proses-wide
  (meniru `InProcessDexGate.withMergePermit`) yang membatasi berapa merge
  boleh jalan bersamaan berdasar `maxHeap / ~96MB per merge`. Bila tak ada
  credit, tunggu; jangan OOM host.
- Hasil yang diharapkan: N bucket berubah -> merge ~1/N kali durasi full, bukan Nx.

### 2. Fork heuristics (kapan archive/merge di-fork ke dalvikvm)

Sekarang: in-process selalu; fork hanya fallback saat error sistem.

Rancangan:
- Archive (per-class) murah: TETAP in-process (reuse classloader/shared provider).
- Merge (puncak memori) di-fork BILA input bucket besar. Ambang: fork bila
  total input kelas merge > ~8MB (meniru `ARCHIVE_FORK_DEFAULT_MB`) ATAU
  `availableMemory < 1.5 * -Xmx fork`.
- -Xmx fork: ladder `2048/1536/1024/768` MB, pilih tertinggi yang `availRAM >= 1.5*Xmx`
  (meniru `forkAffordable()` CodeAssist). Gagal semua -> in-process dengan heap gate ketat.
- Argumen D8 lewat `@argfile` (guard panjang argv dalvikvm).

### 3. Library content-hash archive (library di-dex per-class + cache hash)

Sekarang: tiap library jar di-dex SATU KALI jadi 1 dex utuh
(`cacheDir/libdex/<md5>/classes.dex`) -> reuse lintas build.

Kelemahan: 1 jar = 1 dex monolitik; tak bisa incremental per-class; jar besar
lambat saat pertama kali dex; dua jar konten-identik di-dex dua kali.

Rancangan (meniru `SharedLibraryDexer`):
- Tiap jar -> bucket dir `<libdex>/<sha256>/` (hash KONTEN, bukan md5 path).
- Di dalam bucket: per-class intermediate dex (`DexFilePerClassFile`) + `.globals`,
  sama seperti app-dex, plus sentinel `.dexbucket` penanda selesai.
- Jar konten-identik berbagi bucket (dedup by hash) -> di-dex sekali.
- Dispatch LARGEST-first (jar kelas terbanyak duluan) ke worker pool bounded.
- Saat merge app: library per-class dex ikut jadi input merge bucket (bukan
  dex monolitik) -> desugaring lintas-library ditangani D8 di tahap merge.
- `.dexbucket` hilang/korup -> rebuild bucket; `.dex` per-class bisa di-stat
  ulang (tanpa buka jar) untuk deteksi completeness.

Dampak: library tak berubah -> nol kerja ulang; library berubah -> hanya class
berubah yang di-re-dex, bukan seluruh jar.
- ModuleConfigActivity: pengaturan package/version/sdk/java + mode dex + daftar
  dependency (tambah/hapus, simpan ke module.toml [dependencies]).

## ViewBinding host (fix error CodeAssist)
CodeAssist meng-generate `*Binding.java` (gen-view-binding) utk layout host yang
implements `androidx.viewbinding.ViewBinding`. Karena library `androidx.viewbinding`
TIDAK ada di Maven, interface-nya disediakan sbg source host:
  app/src/main/java/androidx/viewbinding/ViewBinding.java
Jadi binding hasil generate CodeAssist bisa compile. (Pendekatan "dari aset".)

## ViewBinding - pemahaman yang diluruskan
- CodeAssist MENYEDIAKAN viewbinding via dependency `androidx.databinding:viewbinding`
  (package class-nya tetap `androidx.viewbinding.ViewBinding`). TIDAK tersedia sbg
  `androidx.viewbinding:viewbinding` (yang 404); group-nya `androidx.databinding`.
- Host PAKAI viewbinding di Activity-nya (ActivityXxxBinding.inflate) -> module.toml
  host punya `androidx.databinding:viewbinding:8.7.3`.
- Asset viewbinding utk BUILD PROYEK USER: `assets/viewbinding/viewbinding.jar`
  (classes.jar hasil aar, berisi androidx/viewbinding/ViewBinding.class + ViewBindings).
  ToolchainManager ekstrak ke filesDir; BuildPipeline tambahkan ke classpath ECJ & dex
  saat proyek user punya res/layout (jadi host tak perlu unduh lagi per-proyek).
- ViewBindingGen tetap generate `<Layout>Binding.java` (sintesis) dari layout proyek user.

## Activity host (semua memakai ViewBinding)
- MainActivity -> ActivityMainBinding
- CreateProjectActivity -> ActivityCreateProjectBinding
- EditorActivity -> ActivityEditorBinding
- ModuleConfigActivity -> ActivityModuleConfigBinding

## Fix sesi terakhir
- ecj.dex.zip kini menyertakan RESOURCE (messages.properties dll, 101 file)
  selain classes.dex, karena ECJ butuh resource bundle saat dijalankan dalvikvm
  (sebelumnya MissingResourceException). Dibuat build-time (self-dex + copy resource).
- EditorActivity: daftar file besar + KONSOL KECIL (150dp) di bawah + auto-scroll
  (scrollConsole.fullScroll(FOCUS_DOWN)).
- ToolchainManager.ensure(): cepat, skip ekstrak jika toolchain sudah siap (ready()).

## ECJ di ART (fix MissingResourceException) - penting
batch `org.eclipse.jdt.internal.compiler.batch.Main` di Android error
MissingResourceException (butuh messages.properties yg tak terbaca classloader ART).
SOLUSI (cara CodeAssist ImageFreeJavaCompiler): pakai compiler INTERNAL ECJ
(`org.eclipse.jdt.internal.compiler.Compiler`) + INameEnvironment classic, TANPA
batch Main -> tidak butuh messages.properties.
- EcjCompilerMain.java (port ImageFreeJavaCompiler) di-dex bersama ecj ke
  assets/ecj.dex.zip.
- JvmRunner.ecj memanggil `EcjCompilerMain` (bukan batch.Main).
- Format EcjCompilerMain: level(8/11/17/21) outDir classpath(a:b:c) src...
- ecj.dex.zip berisi classes.dex (ecj+shim+EcjCompilerMain) + resources.

## Fix ViewBindingGen (cast double-open paren)
Bug: render `return new X(Type) rootView` (kurang 1 buka kurung) -> seharusnya
`return new X((Type) rootView)`. Diperbaiki: args mulai `"(("` (constructor + cast).

## Fix D8 nest-mate
Pindahkan class Field & Binding di ViewBindingGen dari static nested -> top-level
(package-private, file sama) agar D8 tidak error "requires its nest mates".
Ini mengikuti pelajaran proyekbuild.md (nested class -> top-level).

## Fix package duplicate AndroidManifest.xml
packageApk menyalin resources.ap_ (aapt2 link sudah berisi AndroidManifest.xml)
lalu MENAMBAH AndroidManifest.xml dari file -> duplicate entry. Hapus tambahan
manual; manifest diambil dari resources.ap_ saja.
copyZipEntries kini mempertahankan method STORED/DEFLATED (resources.arsc & file
penting tetap uncompressed / mmap-friendly, sesuai ApkPackaging CodeAssist).

## Fix sign exit=134 (W^X writable dex)
apksig.dex.zip tidak di-make read-only -> ART tolak writable dex pada command-line
VM classpath (W^X: SecurityException) -> SIGABRT exit 134. Fix: makeReadOnly
apksig.dex.zip di ToolchainManager.ensure() (sama seperti ecj & r8).

## Konsol modern (tab LOG / PROBLEM)
EditorActivity: konsol bawah satu panel dgn 2 tab:
  - LOG: semua proses + timing ("[time] ...", "[ECJ] ...", dll).
  - PROBLEM: error (dari Util.Log.problem) + badge jumlah error.
  Auto-scroll, tombol salin. Build selesai otomatis pindah ke tab PROBLEM jika ada error.

## Auto-install APK setelah build sukses
- EditorActivity: setelah build SUKSES -> otomatis buka installer (FileProvider
  content uri, ACTION_VIEW application/vnd.android.package-archive).
- Manifest: provider FileProvider (authority com.ikram.apkbuild.fileprovider),
  res/xml/file_paths.xml, permission REQUEST_INSTALL_PACKAGES.
- Jika GAGAL (1 error saja) -> BuildPipeline return false, APK tidak di-install
  ("[selesai] GAGAL - APK dibatalkan"), dan fallback minta izin install unknown apps.
- BuildPipeline sudah fail-stop: tiap tahap (resource/ECJ/jar/dex/package/sign)
  return false saat gagal; outApk hanya ditulis saat signing sukses.

## Fix sign ClassNotFoundException ApkSignerTool
apksig 8.x TIDAK punya CLI `ApkSignerTool` (hanya API `ApkSigner`). Buat
`ApkSignEntry` (sign via API ApkSigner: keystore->SignerConfig->sign, v1+v2),
di-dex bersama apksig -> assets/apksig.dex.zip. JvmRunner.apksig panggil
`ApkSignEntry in out keystore alias storepass keypass`.

## Fix sign KeyStoreException JKS not found
ART (Android) TIDAK punya JKS KeyStore (hanya PKCS12/BKS). debug.keystore (JKS)
di-convert build-time -> debug.p12 (PKCS12), dibundle sbg asset. ApkSignEntry baca
PKCS12. ToolchainManager ekstrak debug.p12; BuildPipeline pakai debugP12().

## In-process (muat asset) + fallback fork - ECJ/D8/apksig
Meniru android-code-2: tool inti dijalankan IN-PROCESS dengan MEMUAT ASSET dex
yang sudah dibundle (ecj/r8/apksig.dex.zip) via DexClassLoader, TANPA mengunduh /
men-dex library ke APK host. Entry dipanggil lewat method NON-EXIT (`run`), jadi
TIDAK ada System.exit -> aman di proses aplikasi. Fork dalvikvm TETAP dipertahankan
sbg cadangan: bila in-process error SISTEM, otomatis beralih ke fork.

### Bagaimana in-process jalan
- ecj.dex.zip  -> `EcjCompilerMain.run(String[])` (compiler internal ECJ, non-exit).
- r8.dex.zip   -> `D8Entry.run(String[])`        (API D8Command+D8.run, non-exit).
- apksig.dex.zip -> `ApkSignEntry.main(String[])` (API ApkSigner; throws, bukan exit).
Entry non-exit (`run`) TELAH ditambahkan & di-rebuild ke ecj.dex.zip & r8.dex.zip
(entry `main` dipertahankan -> fork tetap berfungsi).

### Cara kerja fallback
- BuildPipeline memuat `InProcessTools` (build/) sbg OPSIONAL via refleksi.
- Bila tak ada / tak tersedia -> in-process NONAKTIF, semua via fork (perilaku lama).
- Bila aktif:
  - ECJ/D8/apksig dijalankan in-process dulu (DexClassLoader muat asset dex.zip).
  - method in-process mengembalikan boolean: true = sukses; false = error KONTEN
    user (source salah, class ganda) -> TIDAK di-fallback.
  - method in-process melempar ToolRuntimeError saat error SISTEM -> BuildPipeline
    menangkap -> otomatis FALLBACK ke fork yang setara.

### Apa yang diubah (sesi ini)
- `build/InProcessTools.java` BARU: DexClassLoader + redirect System.out/err ke
  konsol + panggil entry non-exit; throw ToolRuntimeError utk error sistem.
- `build/BuildPipeline.java`: tiap tahap coba in-process dulu -> fallback fork
  (runEcj/dexLibrary/dexApp/runSign + helper invokeBool/loadInProcess).
- assets/ecj.dex.zip & r8.dex.zip di-rebuild (cadangan asli di backup-assets/*.orig):
  - ecj.dex.zip: EcjCompilerMain punya `run(String[])` non-exit (main delegasi).
  - r8.dex.zip : tambah classes3.dex = `D8Entry` (D8Command+D8.run non-exit).
  - apksig.dex.zip TIDAK diubah (ApkSignEntry.main throws, bukan System.exit).
- `ToolchainManager`: versi toolchain -> v7-inproc (paksa ekstrak ulang asset baru).
- `InProcessTools` + `EditorActivity`: cache classloader STATIS (meniru android-code-2 cachedEcj/cachedD8, ~28s D8 muat) sekali per proses; `clearCache()` di onDestroy. Ecj/r8/apksig.dex.zip dimuat ulang bila ukuran/modtime berubah.

Catatan jujur: sintaks & logika entry non-exit teruji di JVM (EcjCompilerMain.run &
D8Entry.run mengembalikan exit code tanpa System.exit; menghasilkan class/dex).
Jalur runtime ART (DexClassLoader muat ecj/r8/apksig.dex.zip di proses host, ecj &
D8 & apksig jalan in-process) butuh uji perangkat nyata.

## Indexing saat buka proyek + ECJ incremental (meniru android-code-2)
- Buka proyek (EditorActivity): tombol "Build" ditiadakan sementara ("Mengindeks..."),
  jalankan `BuildPipeline.warm()` di background:
  - muat & cache classloader ecj/r8/apksig (r8 ~28s) sekali -> build pertama cepat
    (InProcessTools.warm; classloader statis CACHED).
  - resolve library ke cache disk (unduh sekali, reuse lintas build).
  Selesai -> tombol Build aktif. startBuild ditahan bila masih indexing.
- ECJ incremental: saat hanya file .java tertentu berubah, ECJ compile hanya file
  itu; `classesDir` (class lama) kini disertakan sbg classpath supaya file subset
  bisa resolve class lain (fix bug "cannot be resolved" saat incremental).
- Dex app: skip total bila bytecode tak berubah (class-hash); dex library tetap
  di-cache per konten (libdex/<hash>).

### Fix sesi: apksig in-process "length=5; index=5" + log detail
- InProcessTools.apksig kirim argumen ke ApkSignEntry.main; fork kirim 6 arg
  (in out keystore alias storepass keypass), tapi in-process cuma 5 -> ApkSignEntry
  baca args[5] -> ArrayIndexOutOfBounds "length=5; index=5" -> fallback fork.
  Fix: kirim 6 arg (PKCS12: keypass = storepass). Fallback tetap otomatis ke fork.
- Error in-process kini tampil LENGKAP (class + pesan + stack trace via logErr/describe),
  bukan cuma getMessage() yang sering kosong, supaya diagnosa tidak buta.

## Menu Library (unduh + pilih dependency) - meniru CodeAssist
- MainActivity: tombol "Library" di header -> LibraryActivity (daftar katalog library
  Maven umum). Ketuk = unduh via MavenResolver ke cache disk filesDir/libraries (dipakai
  lintas proyek; tidak unduh ulang bila sudah ada). Yang ada ditandai centang.
- CreateProjectActivity: tombol "+ Tambah Library" -> dialog CHECKLIST pilih library
  (AlertDialog.setMultiChoiceItems) -> disimpan, diteruskan ke ProjectCreator ->
  ditulis ke module.toml [dependencies].implementation.
- LibraryCatalog: katalog koordinat library umum + helper downloaded()/download().
- Cache ala CodeAssist: MavenResolver sudah cache unduhan disk (filesDir/libraries) &
  BuildPipeline cache dex library per-konten (libdex/<hash>) -> library tak di-unduh
  ulang / tak di-dex ulang antar build. ProjectCreator kini terima daftar library utk
  ditulis ke module.toml.

Catatan: file Activity/UI (LibraryActivity, CreateProjectActivity, MainActivity,
LibraryAdapter, layout) tak bisa diverifikasi compile di JVM (butuh ViewBinding &
framework Android); divalidasi via CodeAssist saat build host. XML layout sudah
divalidasi well-formed.
- Konflik dependency "newest-wins": BuildPipeline.newestWins() menyaring koordinat yg
  group:artifact sama -> pilih SATU versi tertinggi (banding token numerik/qualifier),
  meniru CodeAssist ConflictPolicy.NEWEST. Tanpa resolve transitif (host sederhana);
  berlaku utk coords yg di-list module.toml.

## Sesi: mode parallel, resource merger, upgrade in-process D8

### Parallel merge (opsional, checkbox di pengaturan proyek)
- `ProjectConfig.parallelDex` (bool) tersimpan di module.toml `[build] parallelDex`.
- Checkbox "Merge D8 paralel" di ModuleConfigActivity, terpisah dari radio dexMode (auto/full/incremental).
- Saat aktif & >1 bucket berubah: tiap bucket merge di-FORK ke dalvikvm terpisah (`JvmRunner.d8EntryMerge`).
- Xmx ladder 2048/1536/1024/768; pilih tertinggi yg `availMem >= 1.5*Xmx` (forkAffordable).
- Budget = clamp(availMem*0.5/xmx, 1, min(cores,3)) (MAX_CONCURRENT_FORKS=3 ala CodeAssist).
- Argumen besar (>12k char) lewat @argfile (D8Entry.expandArgFiles parse `@file`; D8 sendiri yg baca, bukan dalvikvm).
- GAGAL fork -> fallback sequential in-process (`mergeSequential`), bukan full-dex.
- Off = mode lama sequential (default).

### Resource AAR digabung (ResMerger.java)
- Res SEMUA library AAR + user di-merge ke `merged-res` -> `aapt2 compile --dir` SEKALI -> link.
- Folder values* digabung per-ENTRY (Map O(n), bukan O(n^2)): style/color/attr/string dari tiap library tergabung, override by source terakhir.
- `<declare-styleable>` nama sama di-UNION (gabung child `<attr>`) supaya attr library tak hilang.
- xmlns/tools atribut tag `<resources>` dikumpulkan & dedup by nama (regex) -> hindari duplicate attribute.
- Non-values (drawable/layout/dll) copy per path, source belakangan menang.
- Cache: extract res per AAR ke filesDir/libres/<hash-aar> (GLOBAL, sekali per konten); merged-res & flat per-proyek (`.resfp`, `flat/res.fp`) skip bila tak berubah.
- id resource library di-flatten ke R package app (refer XML/tema jalan).

### Desugar lintas-library (Java 21)
- Saat dex tiap library jar, beri classpath = SEMUA library lain (`--classpath`) supaya D8 tahu class tetangga -> desugar lambda/interface default lebih benar.
- Cache libdex tetap per-konten jar (classpath tak masuk key -> bisa basi bila library lain ubah; diterima utk host).

### Upgrade in-process D8 (D8Entry di r8.dex.zip)
- Shared classpath/library provider: ArchiveClassFileProvider + wrapper non-closing, cache static keyed path|size|mtime (LRU 384), dipakai `addLibraryResourceProvider`/`addClasspathResourceProvider` -> android.jar di-index sekali per proses, bukan per invokasi.
- `finished()` no-op (cegah D8 nutup ZipFile yg dipakai invokasi berikut).
- D8.run(command, executor) utk cap thread internal (`--threads N`; r8 8.13.19 tak ada setThreadCount).
- InProcessTools pass `--threads 1` (ART heap kecil: 1 thread > banyak thread, ukuran CodeAssist).

### Catatan cache merge res
- `extractLibRes` -> filesDir/libres/<hash-aar>/res (GLOBAL, sekali per konten AAR; dipakai lintas proyek).
- Merge library AAR + user -> `work/merged-res` (PER-PROYEK). `.resfp` = fingerprint semua sumber res;
  tak berubah -> SKIP merge (reuse merged-res). `flat/res.fp` -> skip aapt2 compile.
- Artinya: proyek SAMA build kedua = merge & compile res di-skip (cepat). Tiap proyek BARU = merge sekali (~20s) krn
  merged-res per-proyek beda.
- Cache merged-res GLOBAL per kombinasi sumber TIDAK dipasang: manfaat kecil krn dua proyek beda user-res tetap re-merge;
  .resfp per-proyek sudah cukup (developer build ulang proyek yg sama paling sering).
- Ukur dulu build kedua proyek sama; kalau res tak muncul di log (skip), cache jalan & sehat.

### RENCANA: cache merged-library GLOBAL (percepat build pertama proyek baru)
- Masalah: merged-res PER-PROYEK -> tiap proyek BARU merge ulang res library (~20s utk 26 AAR), walau library sama.
- Rencana (pisah dua tahap):
  1. Cache `filesDir/resmerge/<hash-library-set>/res` = hasil merge SEMUA res library (TANPA res user), sekali per kombinasi library. Key = hash konten kumpulan library.
  2. Saat build proyek: copy merged-library cache -> `work/merged-res`, lalu copy res USER (override). Copy stream > parse XML -> hemat ~20s -> ~1s.
- Biaya: hash kumpulan library tiap build (cache juga); copy ribuan file tiap proyek; logika dua-tahap.
- Belum diimplementasi (menunggu konfirmasi: sering bikin proyek baru atau build ulang proyek sama).

---

## Catatan Sesi (2026-09-07) — perbaikan icon & label

### AppCompatActivity (proyek `.mp3`)
- `MainActivity.java`: extends `Activity` -> `AppCompatActivity` (+import).
- Tambah `res/values/themes.xml` (`Theme.App` parent `Theme.AppCompat.Light.DarkActionBar`), set `android:theme` di manifest.
- Wajib: theme turunan AppCompat, kalau tidak `AppCompatActivity` crash.

### Icon launcher (proyek `android-code`)
Penggantian bertahap -> hasil akhir **logo Android vektor XML** (bukan PNG raster).

- Bg icon: `colors.xml` `ic_launcher_background` = `#000000` (hitam).
- Logo: dome kepala + 2 antena condong keluar + 2 mata + garis bawah; warna **putih** `#FFFFFF`, mata **hitam** `#000000`.
- Dua resource vektor (drawable):
  - `ic_launcher_foreground.xml` (dipakai launcher/adaptive, `mipmap-anydpi-v26`) — robot di-scale `0.85` (grup pivot 54,54) biar pas di launcher.
  - `ic_launcher_foreground_full.xml` (dipakai editor/file-manager/legacy, `mipmap/`) — robot ukuran penuh.
- PNG lama `androidide_robot*.png` di `drawable-nodpi` dihapus; semua ikon kini vektor (halus, tak pecah/sinkron).

Geometri (viewport 108):
- Dome kepala: `M26,69 A26,26 0 0 1 82,69 Z` (x26..82).
- Antena kiri `M42,50 L36,51 L32,36 L38,35 Z`, kanan `M72,50 L66,51 L70,36 L76,35 Z` (condong keluar, renggang).
- Mata hitam r3.5 di (44,55) & (63,55). Garis bawah putih `M28,74 h52 v3`.

Catatan perbedaan tampilan: launcher vs editor/file-manager render icon beda (launcher zoom penuh, editor tampil kecil). Karena itu disediakan 2 skala vektor (0.85 vs penuh).

### Label aplikasi
- `strings.xml`: `app_name` "ApkBuild" -> "Android Code". (Package id `com.ikram.apkbuild` TIDAK diubah.)

### Catatan perilaku build (dari log proyek `.mp3`)
Log build menunjukkan perilaku pipeline (BUKAN error):
- `[res] merge 27 sumber -> merged-res (~35-44s)`: aapt2 gabung res library AAR + user; lambat di build pertama, di-skip bila `.resfp` tak berubah.
- Warning `ignoring element 'g' with unknown namespace tools` di `merged_values.xml`: bawaan aapt2 dari XML ikon/library (`<g>` non-android); aman diabaikan, tak menghentikan build.
- `[app-dex] bucket-lite`: D8 bagi class ke beberapa `classes.dex`; `manifest changed` -> re-merge bucket terkait. `[lib-dex]` cache classes.jar per library.
- `SIGN OK` -> `output.apk`; `[selesai] SUKSES`.

Sesi ini di proyek `.mp3`: hanya ganti MainActivity ke AppCompatActivity + tambah theme; build lalu SUKSES (log 51s, 3 dex).

## Catatan Sesi (2026-09-07) — redesign UI tema Sunny Glow

Desain ulang UI host mengikuti tampilan `AndroidCSOfficial/android-code-studio` (ACS):
layout utama jadi lebih modern + terapkan tema **Sunny Glow** (palet resmi ACS/AndroidIDE,
warm-brown/tan). Sumber palet: `theme_sunny_glow.xml` ACS (dari remote, bukan diunduh ke proyek).
Scope: semua layar KECUALI EditorActivity (console gelap `#101418` tetap).

### Warna & tema
- `values/colors.xml`: palet sunny glow penuh (light + dark) — primary `#6F5A4A`,
  primaryContainer `#F9DDC9`, surface/background `#FFFBFF`, varian gelap
  (`primary_dark` `#DCC2AE`, `background_dark` `#1E1B19`, dst). Hapus teal
  `#00695C`/`#00363A`/amber `#FFB300` (warna lama tema "air terjun").
- `values/themes.xml`: `Theme.App` (light, parent `android:Theme.Material.Light.NoActionBar`)
  + `Theme.App.Dark`; set colorPrimary/colorAccent/statusBar/navBar, buttonStyle,
  editTextStyle, radioButtonStyle, checkboxStyle, listViewStyle.
- `values-night/themes.xml` (BARU): `Theme.App` = parent `Theme.App.Dark` → mode malam
  otomatis ikut sistem. Style bernama sama di-override ke varian `.Dark`.
- Style kustom: `Button.SunnyGlow` (filled primary), `Button.Outline.SunnyGlow`
  (border transparan), `Fab.SunnyGlow` (lingkaran 56dp), `IconButton.SunnyGlow`
  (tombol back "‹"), `label` field form.
- Drawable BARU: `bg_project_card` (kartu list rounded 16dp, surface_container),
  `bg_panel` (kartu form rounded 20dp), `bg_btn_outline(-dark)`, `bg_fab(-dark)`;
  varian malam di `drawable-night/`.

### Layout
- `activity_main.xml`: rewrite total. Header baru (judul besar + subtitle + tombol
  "+ Buat" & "Library" kanan atas), ListView jadi kartu (divider transparan + jarak 8dp),
  FAB bulat "+" kanan bawah, empty-state rapi.
- `item_project.xml`: kartu rounded `surface_container`, teks pakai `?attr/textColor*`
  (ikut tema), bukan hardcode teal.
- `activity_create_project.xml`, `activity_module_config.xml`, `activity_library.xml`:
  header konsisten (judul + tombol back "‹"), form/konten di panel rounded `bg_panel`,
  warna teks pakai attr tema.
- Hapus file usang `activity_module_config.xml.new` (0 byte).

### Java (handler tombol baru)
- `MainActivity.java`: `binding.fabCreate` → openCreate (tombol create ganda: header + FAB).
- `CreateProjectActivity.java`, `ModuleConfigActivity.java`, `LibraryActivity.java`:
  `binding.btnBack` → finish().

### Catatan teknis
- HOST tetap pakai `Activity` + `android:Theme.Material` (BUKAN AppCompat/Material lib);
  desain tak butuh dep baru. Kelas `com.google.android.material.*` TIDAK dipakai.
- Layout baru pakai FrameLayout/LinearLayout/ListView saja (tak ada ConstraintLayout/
  SwipeRefresh → hindari dep androidx tambahan yang belum tentu di classpath CodeAssist).
- Mode malam: tombol/outline/fab di-override lewat parent `.Dark` di `values-night`
  (style XML tak bisa resolve via `?attr`, jadi pakai file night).
- BELUM diuji build host di CodeAssist; binding baru (`btnBack`, `fabCreate`) perlu
  diverifikasi saat build (gen ViewBinding). Validasi lain (XML well-formed, ref
  drawable/color/style resolve, id binding cocok) sudah lewat.

### Fix crash inflate (mode malam)
- Gejala: `RuntimeException ... InflateException ... You must supply a layout_width
  attribute` saat buka CreateProjectActivity (device mode malam).
- Akar: `values-night/themes.xml` sempat punya `<style name="label" parent="label">`
  → parent self-ref (resolv ke style night itu sendiri) → style `label` jadi kosong
  (layout_width hilang) → TextView `style="@style/label"` tanpa inline width gagal inflate.
- Fix: hapus override `label` dari values-night; warna `label` ganti ke
  `?android:attr/textColorSecondary` (otomatis ikut mode, tak butuh duplikasi).
- Pelajaran: style XML override malam TIDAK boleh `parent` ke nama yang sama
  (cross-variant self-loop = style kosong). Override malam cukup `parent="<Nama>.Dark"`
  yang terdefinisi di `values/`.

## Catatan Sesi (2026-09-07) — EditorActivity sunny glow + tab INDEX/KONSOLE + icon

EditorActivity dirombak agar seragam sunny glow & "modern" (mengikuti ACS: header
icon-aksi, file & konsol dipisah via tab):

### Layout `activity_editor.xml` (rewrite)
- Header ala ACS: ImageButton back "‹" + nama proyek + aksi icon-only:
  `btnConfig` (gear), `btnLibs` (buku/library), `btnBuild` (play bulat filled primary).
  Tombol teks dihapus → semua icon (pakai vector drawable sendiri, tak butuh lib icon).
- Tab mode atas: **INDEX** (daftar file) vs **KONSOLE** (log build) — dua konten
  terpisah (ListView vs panel konsol), bukan menu sempit.
- Panel konsol tetap gelap (`#101418`), tab LOG/PROBLEM + btnCopy icon di dalamnya.

### Drawable vector BARU (framework `VectorDrawable`, tanpa dep)
`ic_arrow_back`, `ic_build_play`, `ic_library`, `ic_settings`, `ic_copy`,
`ic_folder`, `ic_file_list`. Warna dasar hitam/putih; di-warnai ulang via
`android:tint` (icon header → `?android:attr/textColorPrimary`, build → putih,
btnCopy konsol → `#D4E3EF`).
- `bg_ripple.xml`: ripple oval borderless utk ImageButton icon.

### Style themes.xml
- `IconButton.SunnyGlow`: ImageButton icon-only (44dp, bg `bg_ripple`).
- `IconButton.SunnyGlow.Filled`: variant bulat filled primary (dipakai btnBuild).

### EditorActivity.java
- `btnBuild`, `btnConfig`, `btnLibs`, `btnCopy`, `btnBack` → ImageButton (ViewBinding
  re-gen type). Field `buildBtn` jadi `android.view.View` + cast ke ImageButton utk
  setImageResource (ganti dari setText — ImageButton tak punya).
- Status indexing tampil di `txtProjName` ("nama (mengindeks...)"), bukan di tombol.
- `setMode(index)`: toggle INDEX/KONSOLE (visibility + warna/bold tab).
  Build jalan otomatis pindah ke mode KONSOLE. `resolveColor` ambil warna tab dari
  theme (pakai `obtainStyledAttributes`, bukan `resolveAttribute` — textColorPrimary
  adalah ColorStateList).
- `btnBack` → finish().

### MainActivity
- `btnLibrary` (layout activity_main.xml) jadi ImageButton icon buku (ic_library),
  header lebih ringkas; handler Java tak berubah (setOnClickListener tetap).

### Catatan teknis
- Icon semua vector buatan sendiri (path Material) — tanpa dependency icon library.
- Konsol & LOG/PROBLEM tetap tema gelap `#101418`/`#1B232A`/`#263238` (keputusan:
  area log biar kontras & "IDE-dark"), kontras dengan kulit sunny glow.
- `tabIndex`/`tabConsole`: textStyle aktif bold + warna aktif via theme; tak hardcode
  warna sunny agar ikut mode malam.
- BELUM diuji build CodeAssist; ViewBinding type baru (ImageButton utk btnBuild/
  btnConfig/btnLibs/btnCopy/btnLibrary/btnBack) diverifikasi saat build.

### Migrasi ke AppCompatActivity (host)
- module.toml sudah punya `androidx.appcompat:appcompat:1.7.0` + material 1.12.0.
- 5 Activity host diganti `extends Activity` -> `extends AppCompatActivity`:
  MainActivity, CreateProjectActivity, EditorActivity, ModuleConfigActivity,
  LibraryActivity (import `android.app.Activity` dihapus).
- WAJIB: AppCompatActivity butuh tema turunan AppCompat. Parent tema diubah:
  - `Theme.App` (light)  : `android:Theme.Material.Light.NoActionBar` ->
    `Theme.MaterialComponents.Light.NoActionBar`
  - `Theme.App.Dark`     : `android:Theme.Material.NoActionBar` ->
    `Theme.MaterialComponents.NoActionBar`
  (MaterialComponents = turunan AppCompat; dep material sudah ada di module.toml.)
- Catatan: layout host tak pakai widget `com.google.android.material.*` (framework
  views), jadi tak wajib komponen M2 penuh; tema MaterialComponents cukup utk
  AppCompatActivity + warna sunny glow tetap di-set via item tema.
- ProjectCreator.java (template proyek user) TIDAK disentuh — itu generate kode
  proyek user, bukan host.

### Fix tema: kembali sunny glow + konsol sunny (AppCompatActivity/Material)
- Gejala: setelah ganti parent tema ke `Theme.MaterialComponents.*`, warna tampil
  default M2 (ungu/teal), bukan sunny glow.
- Akar: komponen AppCompat/Material resolve attr M2 (`colorPrimary` TANPA prefix
  `android:`), bukan `android:colorPrimary`. Item framework saja tak cukup; warna
  M2 bawaan MaterialComponents (ungu) menimpa.
- Fix `themes.xml`: Theme.App & Theme.App.Dark kini set DUA versi item — attr M2
  (`colorPrimary`, `colorPrimaryVariant`, `colorSecondary`, `colorSurface`,
  `colorOnSurface`, `colorError`, dst) DAN attr framework (`android:colorPrimary`,
  `android:colorAccent`, `android:textColor*`, dst). Komponen appcompat ikut sunny.
- `values-night/colors.xml` BARU: override nama warna generik (`primary`,
  `surface`, `on_surface`, `surface_container`, `outline`, `error`, dst) ke varian
  gelap. Efek: layout XML yang ref `@color/surface_container` dll otomatis ikut
  mode malam (tanpa file drawable-night terpisah).
- Konsol (activity_editor.xml) dirombak jadi SUNNY GLOW (bukan hitam):
  - panel bg `surface_container_low`, header bar bg `surface_container`
    + judul "Log build" + tombol salin, tab LOG/PROBLEM warna sunny
    (`primary`/`error`/`on_surface_variant`), divider `outline`, teks log
    `on_surface` monospace.
  - `selectTab()` di EditorActivity tak lagi hardcode warna gelap
    (#1B232A/#263238/#00E676); pakai `getColor(R.color.*)` (ikut mode via
    override night). `error` = sunny red (#B3261E light, #F2B8B5 dark).
  - Hapus `console_bg`/`console_text` (tak terpakai lagi).
- Catatan: masih ada warna suffix `_dark` (primary_dark, surface_dark, dll) utk
  drawable/style `.Dark`; kini sebagian redundan krn override night sudah menangani
  nama generik — dibiarkan agar `.Dark` style tetap eksplisit & tak regresi.

### Editor: buang tab INDEX/KONSOLE — kembali log di bawah index (1 layar)
- Layout activity_editor.xml disederhanakan: header icon + label "FILE" + ListView
  daftar file (besar, weight 1) + panel konsol di BAWAH (selalu tampil, sunny glow).
  Tab menu INDEX/KONSOLE & FrameLayout contentArea DIHAPUS (bukan 2 mode ganti-ganti).
- Konsol: bg `surface_container_low`, header bar "Log build" (`surface_container`)
  + tombol salin icon, tab LOG/PROBLEM sunny, divider, ScrollView log 150dp.
- EditorActivity.java: hapus `setMode()` + `resolveColor()` + listener
  tabIndex/tabConsole; startBuild tak lagi pindah mode (log di bawah langsung
  terlihat saat build). Handler tabLog/tabProblem tetap (switch isi konsol).
- Panel konsol kini `wrap_content` (bukan 240dp + visibility toggle).







## Catatan Sesi (2026-09-08) — Menu Pengaturan + pilih Tema (Sunny Glow / Material You)

### Yang ditambahkan
- Menu Pengaturan di MainActivity: icon gerigi (ic_settings) di header kiri, buka
  SettingsActivity. Berisi daftar sub-menu; sekarang baru "Tema" — wadah siap
  ditambah menu lain nanti (cukup baris di activity_settings.xml + handler di Java).
- **Pilih Tema** (di dalam menu Tema, bukan dialog):
  - Palet: **Sunny Glow** (tema bawaan) / **Material You** (warna dinamis dari
    wallpaper; aktif hanya Android 12+, di bawah itu di-disabled).
  - Mode terang/gelap: **Ikut sistem** / **Selalu terang** / **Selalu gelap**
    (paksa lewat `AppCompatDelegate.setDefaultNightMode`).
  - Pilihan aktif di-highlight (opacity penuh), yang tak aktif lebih redup.
- Pilihan tersimpan di SharedPreferences (`appearance`), berlaku lintas buka app.

### Cara kerja
- `Themes.java`: prefs + konstanta (palet SUNNY/MATERIAL, night SYSTEM/LIGHT/DARK)
  + `applyNight()` (AppCompatDelegate, WAJIB sebelum super.onCreate) &
  `applyPalette()` (DynamicColors Material, sesudah super.onCreate).
- `ThemedActivity.java`: base activity baru; semua 6 activity host kini
  `extends ThemedActivity` (bukan AppCompatActivity langsung). isi onCreate:
  `Themes.applyNight(this)` -> super -> `Themes.applyPalette(this)`.
  Jadi tiap layar otomatis ikut pilihan tema.
- `SettingsActivity.java`: daftar menu; pilihan baris -> set prefs -> recreate()
  -> tema ganti seketika.
- DynamicColors = `com.google.android.material.color.DynamicColors` (material
  1.12.0 sudah ada di module.toml). Diterapkan per-activity via
  `DynamicColors.applyToActivityIfAvailable` — OVERRIDE palet sunny glow utk
  layar ini (view komponen Material ikut warna wallpaper).

### Catatan / belum diuji
- Belum diuji build CodeAssist (validasi statik lolos: semua binding.<x>,
  R.id, @drawable/@color/@style/@string resolve; brace balance ok).
- Material You meng-override warna sunny glow saat aktif; warna sunny glow yang
  di-hardcode via @color/ (mis. editor konsol `surface_container_low`) TIDAK ikut
  berubah ke palet Material (tetap sunny/dark sesuai mode). Baris list/file lain
  yang pakai @color/nama-generik juga tetap sunny. Yang berubah = komponen yang
  resolve `?colorPrimary`/`colorSurface` dll dari tema (tombol, highlight, dialog).
  Kalau nanti mau Material You "penuh" (list item ikut warna), perlu migrasi item
  dari @color/generik ke attr tema (`?attr/colorSurface` dll) — catat, belum dikerjakan.
- EditorActivity tetap setelan konsolnya pakai `getColor(R.color.*)` -> warnanya
  ikut mode siang/malam tapi TIDAK ikut Material You (tetap palet sunny). Diputuskan
  biarkan (konsol IDE-kontras), bisa diubah nanti.
- `ic_settings.xml` sudah ada sejak sesi sebelumnya (dipakai juga di EditorActivity?).

## Catatan Sesi (2026-09-09) - Preset tema Blue Wave

Ditambah preset palet ketiga: **Blue Wave** (biru, dari AndroidIDE-R theme_blue_wave.xml) di menu Pengaturan > Tema, di samping Sunny Glow (default) & Material You.

### Cara kerja baru (tema dinamis via ?attr)
- Warna inti TIDAK lagi di-hardcode @color di drawable/style. Drawable solid/stroke (`bg_project_card`, `bg_panel`, `bg_fab`, `bg_btn_outline`) & style tombol (Button.SunnyGlow, Fab, Outline, EditText, Radio, Check) kini baca `?attr/colorPrimary`, `?attr/colorSurfaceVariant`, `?attr/colorOutline`, `?attr/colorOnPrimary` dst dari tema aktif.
- 4 tema penuh di values/themes.xml: Theme.App (sunny light), Theme.App.Dark, Theme.App.BlueWave (light), Theme.App.BlueWave.Dark. Masing2 set role M2 + android:*. Sunny theme tambah role colorSurfaceVariant/colorOnSurfaceVariant/colorOutline (sebelumnya tak ada).
- ThemedActivity.onCreate: Themes.applyTheme() panggil setTheme(style sesuai palet+mode) SEBELUM super.onCreate + setDefaultNightMode; Themes.applyPalette() sesudah (Material You). Palet: SUNNY=0, BLUE=1, MATERIAL=2.
- values-night/themes.xml disederhanakan: tinggal Theme.App & Theme.App.BlueWave parent ke varian .Dark (style .Dark per-tombol dibuang krn tak perlu lagi - ?attr sudah ikut tema).
- drawable-night/ dihapus (dupe solid sunny). Direktori kosong dibuat lagi krn script validasi mengharapkannya.

### Yang TIDAK ikut preset (keputusan lama, tetap)
- Warna editor/konsol & tab yg di-hardcode @color (EditorActivity & activity_editor.xml: surface_container_low, error, dll) tetap palet sunny per mode siang/malam - tak ikut Blue Wave/Material. Konsol IDE-kontras, biarkan.

### Belum diuji
- Build via CodeAssist (validasi statik PASS). Material You mungkin tak menimpa colorSurfaceVariant/colorOutline (cards bisa tetap sunny di bawah Material) - sudah begini sejak awal, catat batas ini.

## Catatan Sesi (2026-09-09) - Katalog library dipersingkat

LibraryCatalog.LIBRARIES dipangkas jadi 3 library utama: appcompat 1.7.0, material 1.12.0, media3-exoplayer 1.7.0. Efek otomatis di semua pemakai katalog: menu Unduh Library (LibraryActivity), dialog Pilih Library (CreateProjectActivity), dialog Library terpasang (ModuleConfigActivity promptLibraries). Versi yang tak terpakai (constraintlayout, recyclerview, okhttp, dll) dibuang. Kalau nanti butuh lagi, tinggal tambah baris koordinat. Validasi statik PASS.

## Catatan Sesi (2026-09-11) - Cacat & perbaikan cache fingerprint

### BUG FATAL (sudah diperbaiki): fingerprint resource pakai mtime, bukan isi
Gejala: APK memuat string LAMA. Contoh nyata: `stat_time_hours` di arsc masih `%1$s`
padahal kode kirim 2 argumen -> `java.util.MissingFormatArgumentException: Format
specifier '%2$d'` saat Activity dibuka (StatisticsActivity.formatDuration). Proyek
yang tak pernah menyentuh cache `work/` terlihat "aman" (link selalu jalan), jadi
bug ini menyamar sebagai "bug proyek tertentu".

Akar: `addFp` menyusun fingerprint dari `length + mtime`. Kalau editor/FUSE
mempertahankan mtime+size (atau edit <1 dtk), edit `res/values/strings.xml`
TERLIHAT tak berubah -> 3 cache ini di-reuse padahal sumber berubah:
- `work/.resap-fp` -> reuse `resources.ap_` (aapt2 link di-skip)
- `work/.userfp` -> reuse `flat/user.zip`
- resflat library (`ResMerger`) -> reuse `lib.zip`

Perbaikan (BuildPipeline.java):
- `addFp` kini pakai `fpHash` = hash ISI file (bukan mtime). Memo `size:mtime -> hash`
  (hashMemo) tetap ada supaya tiap file cukup dibaca sekali per build.
- `fpHash` SELALU bentuk sama (contentHashStrong = SHA-256 dari md5+length).
  Sebelumnya cold/warm bisa beda bentuk -> fingerprint tak deterministik.
- `hashOf` dijadikan murni (tak lagi panggil Util.md5 dua kali).
- `dexAppBuckets`: `classRelPath` + `cachedHash` dihitung SEKALI per class (dulu 2-3x),
  hasil dipakai ulang untuk manifest, cek nest, dan tulis `.dex.hash`.

### ATURAN untuk AI berikutnya
- JANGAN pernah pakai `length + mtime` untuk cache/fingerprint yang menyangkut
  hasil build. mtime bisa diam walau isi berubah. Pakai hash isi (`fpHash`/`cachedHash`).
- Memo hash valid HANYA dalam satu build. `hashMemo.clear()` di `build()` itu wajib.
- Setiap cache (`.resap-fp`, `.userfp`, resflat sentinel, bucket manifest, `.dex.hash`)
  harus bisa di-INVALIDASI saat format/logikanya berubah -> naikkan versi terkait
  (`BUCKET_CACHE_VERSION`, `RES_MERGE_VERSION`) atau ubah nama file fingerprint.
- Setelah upgrade format fingerprint, build pertama akan "link ulang sekali" (wajar),
  bukan bug. Kalau perlu paksa: hapus `work/.resap-fp` + `work/.userfp`.
- Verifikasi cache class: jumlah `classes/**/*.class` harus == jumlah
  `dexcache/**/*.dex` (dan jumlah `.dex.hash`). Bucket `buckets/g<N>/classes.dex`
  semua harus ada. `.globals` boleh 0 (tak ada synthetics).

### Sisa optimasi (belum dikerjakan, bukan bug)
- `read(.dex.hash)` masih per-class saat cek nest (bisa di-memo).
- `dexBasePath` dihitung berulang.
- `hashOf` mentah masih dipakai di jalur jar/AAR saat impor (jarang, tak masalah).
- Library content-hash archive (README bagian "Rancangan" no.3) belum diimplementasi:
  masih 1 jar = 1 dex monolitik.

## Catatan Sesi (2026-09-11 lanjutan) - Optimasi build (bukan fitur)

Backup: `BuildPipeline.java.bak-20260911-124947` (sebelum perubahan ini).

Perf: total build incremental nx-music 22.8s. Biang bukan D8 (semua bucket reuse),
tapi I/O berulang + fingerprint dihitung banyak kali.

Perubahan:
- `dexAppBuckets`: `classRelPath` + `cachedHash` dihitung SEKALI per class ke
  `classRelAll`/`classHashAll` (dulu 2-3x per class + walk classes/ 3x).
- Verifikasi dropped-class: pakai map yang sudah ada (tak walk ulang), dan
  DI-SKIP bila tak ada bucket yang re-merge (`anyReMerged`). Aman: tanpa D8 jalan,
  mustahil ada class dropped. Kasus "dex hilang manual" tetap tertangkap saat build
  berikutnya yang menyentuh bucket itu.
- `compileResources`: `dirFingerprint` di-dedup. `libResFp` & `userResFp` dihitung
  sekali, dipakai untuk kunci resflat, `.userfp`, dan `fpRes` (dulu 3-4x rekursif
  penuh atas ribuan file res AAR -> ini biang 3.5s di tahap "resource", BUKAN aapt2
  link (log: `[res] link reuse`)).

Hasil compile ECJ: exit 0. Self-check dedup+gate: lulus.

Catatan: `fpHash` (hash isi) di `addFp` membuat fingerprint benar tapi MEMBACA isi
tiap file res. Dedup di atas menutup sebagian besar biaya. Kalau masih lambat,
langkah berikut: cache hash LIBRARY res (AAR statis) secara persisten antar build
(JANGAN user res - rawan mtime palsu).

## Catatan Sesi (2026-09-13) - ViewBindingGen: tag custom tak dikenali

Backup: `ViewBindingGen.java.bak-20260913-063339`.

### Gejala
Build proyek user (Nx Music 2) gagal dengan error ECJ:
```
ActivityPlayerBinding.java:88: error: spectrum_view cannot be resolved or is not a field
```
dan field binding bertipe salah:
```java
public final android.view.View spectrumView;   // harusnya com.nx.music.SpectrumView
```

### Dua penyebab (dua-duanya nyata, beda)
1. **R usang** (bukan bug kode) - `R.class` lebih tua daripada `R.jar`, sehingga
   `R.id.spectrum_view` tak ada. Selesai setelah hapus + rebuild (R di-generate
   ulang).
2. **BUG ViewBindingGen `fqViewType`** - daftar tag di-hardcode 12 buah, sisanya
   jatuh ke `default: "android.view.View"`. Tag custom / tag di luar daftar
   mendapat tipe `View` di file binding, padahal kode user butuh tipe aslinya
   -> "incompatible types" saat compile.

Tag fallback yang ditemukan di layout Nx Music 2:
`ImageButton, SeekBar, HorizontalScrollView, ListView, RadioButton, RadioGroup,
Switch, com.google.android.material.card.MaterialCardView,
com.google.android.material.bottomnavigation.BottomNavigationView,
com.nx.music.SpectrumView`

### Perbaikan
- Tag **FQN** (mengandung titik) -> pakai apa adanya sebagai tipe. Ini menutup
  semua view custom dan view library (`androidx.*`, `com.google.android.*`)
  tanpa perlu mendaftarkannya satu per satu.
- Tambah tag widget standar yang tadinya fallback: `ImageButton`, `CheckBox`,
  `RadioButton`, `RadioGroup`, `Switch`, `SeekBar`, `ProgressBar`, `Spinner`,
  `GridLayout`, `TableLayout`, `TableRow`, `HorizontalScrollView`, `ListView`,
  `GridView`, `Space`, `ViewGroup`.
- `default` tetap `android.view.View` (aman, tapi jarang terpakai sekarang).

### Belum diuji
Fix di atas **belum di-build ulang** saat catatan ini dibuat. Verifikasi:
rebuild `android-code`, build ulang proyek user, lalu pastikan
`ActivityPlayerBinding.java` memakai `com.nx.music.SpectrumView` (bukan
`android.view.View`).

### Sisa (belum dikerjakan, bukan bug)
- `<merge>` sebagai root layout: `fqViewType` mengembalikan `View`, sedangkan
  binding root `<merge>` seharusnya `bind(View)` tanpa inflate. Belum ada layout
  user yang memakai `<merge>`.
- Error parse layout ditelan `catch` di `generate()` (lanjut ke file berikutnya).
  Log `[vb] gagal parse` ada, tapi tak membedakan "XML rusak" vs "layout tak ada
  ID". Dibiarkan supaya diff minimal.

## Catatan Sesi (2026-09-15) - Optimasi: memo hash di jalur fingerprint

Backup: `app/src/main/java/com/ikram/apkbuild/build/BuildPipeline.java.bak-opt-20260915-115417`.

Latar: build incremental 1 file Java (Nx Music 2) = 22.3s, tapi `[time]` yang tercatat
cuma ~10.3s -> ~12s hilang di luar timing. Biang bukan D8 (semua bucket reuse), tapi
fingerprint dihitung berulang atas file yang SAMA, memakai `hashOf` (md5 penuh, tanpa memo).

Perubahan:
- 13 pemakaian `hashOf` di jalur fingerprint diganti `cachedHash` (memo size:mtime -> md5):
  stage resource/compileJava/dex/sign (inputFp + outputFp), `packageInputFp`, `dirFingerprintOf`.
  Efek: `classes/**/*.class` (12MB) dan `dex/*.dex` (18MB) tak lagi di-md5 2-3x per build.
- `hashOf` SENGAJA dibiarkan di: AAR lokal (`localAarFiles`), impor AAR (`explodeLocalAars`),
  dan key cache lib (`ensureLibraryDexCached`/`dexLibraries`). Jalur itu jarang (README
  "Sisa optimasi" sudah menyebut ini tak masalah).
- Hook BARU `Stage.beforeRun(StageRunner)` (default no-op) dipanggil StageRunner tepat
  SEBELUM `run()`. Kelima stage build mengisinya `hashMemo.clear()`.

KENAPA hook itu WAJIB (bukan gaya-gayaan): memo `cachedHash` di-key `length:mtime`. Tahap
build MENULIS file yang juga ia hash sendiri (classes/, dex/, R.jar, output.apk). Di bawah
FUSE /sdcard atau editor yang mempertahankan mtime, memo bisa basi -> fingerprint "tak
berubah" padahal isi berubah -> cache skip -> APK basi. Ini persis kelas bug yang ATURAN
2026-09-11 larang. Timpa memo sebelum tiap run menutup celah itu; memo tetap hidup DI DALAM
satu stage (tempat manfaat terbesar: inputFp+outputFp+dexAppBuckets baca file yang sama).

- Log timing BARU di `StageRunner`: `[time] fp[<nama>]` (biaya hitung fingerprint untuk
  keputusan skip) dan `[time] fp-post[<nama>]` (hitung ulang sesudah run, untuk ditulis ke
  .stage-cache). Ini yang membuktikan gap 12s benar-benar terpangkas.

Self-check: `MemoCheck2` (lulus) - nilai cachedHash == hashOf; 3 panggilan dalam 1 stage =
0 md5 baru; perubahan isi + mtime dipalsukan TERBACA setelah clear; tanpa clear TERLEWAT
(bukti hook wajib). Verifikasi compile: `javac` atas 25 sumber host, 0 error dari
BuildPipeline/StageRunner/Stage.

Belum diverifikasi: angka build nyata di perangkat (butuh buka proyek + tekan Build).

## Catatan Sesi (2026-09-18) - Analisis & perbaikan: build sukses tapi APK tetap crash

Gejala yang dilaporkan: setiap kali **UI baru ditambahkan** ke proyek user (id baru di layout
+ `@string` baru), build selesai `SUKSES`, APK dipasang, lalu aplikasi **FC**:

```
java.lang.NullPointerException: Attempt to invoke virtual method
 'void android.widget.ListView.addHeaderView(android.view.View)' on a null object reference
  at com.nx.music.MainActivity.onCreate(MainActivity.java:97)
```

`findViewById(R.id.list_songs)` mengembalikan `null` padahal `list_songs` ada di layout.
Analisis menemukan **tujuh cacat nyata** di pipeline. Enam di antaranya bisa membuat id baru
tidak sampai dengan benar ke `R`/dex, atau membuat artefak build **tercampur antar build**.

### Bug 1 - `gen/` dihapus lebih dulu, lalu `link reuse` tak menulis R.java

`stageResource()` menghapus `work/gen/` di awal, LALU memanggil `compileResources()`.
Di dalamnya ada jalur cepat:

```java
if (ap_.exists() && fpRes.equals(read(apFpFile))) { ... return true; }   // link reuse
```

Saat jalur ini diambil, `aapt2 link --java` tidak dijalankan, jadi **R.java tidak pernah
ditulis** — sementara `gen/` sudah kosong. `stageCompile()` kemudian hanya bertahan pada
`R.jar` lama, sehingga id/string BARU (`@+id/bar_pulse_1`, `@string/motivation_1`) tidak ada
di `R` → ECJ: *cannot be resolved or is not a field*.

**Perbaikan:** `gen/` tidak lagi dihapus di awal `stageResource()`. Penghapusan dipindah ke
`compileResources()`, tepat SEBELUM `aapt2 link` sungguhan. Bila `link reuse` diambil dan
`gen/R.java` ternyata kosong, reuse **dibatalkan** dan link dijalankan ulang.
Helper baru: `hasAnyRJava(File)`.

### Bug 2 - `R.class` diekstrak dari `R.jar` SESUDAH ECJ

Ekstraksi `R.class` ke `classes/` berada di akhir `stageCompile()`, setelah `runEcj()`.
Pada compile inkremental, ECJ membaca `R$id.class`/`R$string.class` dari `classes/` —
versinya **basi** — sehingga id yang baru ditambahkan dianggap tidak ada, walau `R.jar` dan
`R.java` sudah benar.

**Perbaikan:** ekstraksi dipindah ke **sebelum** ECJ. Pengecekan akhir diganti
`countClassesIn(classesDir) == 0` sebagai deteksi kerusakan.

### Bug 3 - fingerprint `R.jar` tidak menyertakan `resources.ap_`

```java
StringBuilder rFpB = new StringBuilder(String.valueOf(lastResFp));
rFpB.append('#').append(rJavaFiles.size());
for (File f : rJavaFiles) rFpB.append('#').append(cachedHash(f));
```

Ketika `R.java` kosong (efek Bug 1), `rFp` menjadi `lastResFp#0` — nilai yang **sama untuk
build mana pun** yang fingerprint resource-nya kebetulan cocok. Akibatnya `R.jar` LAMA
dipertahankan walau tabel id di `resources.ap_` sudah berbeda → id di `.dex` ≠ id di
`resources.arsc` → `findViewById()` null saat runtime.

**Perbaikan:** hash `resources.ap_` yang sedang dipakai ikut disertakan ke `rFp`. Bila
`R.java` kosong dan `rFp` tidak cocok, `R.jar` + `resources.ap_` + `.resap-fp` dibuang dan
link dipaksa ulang.

### Bug 4 - build GAGAL meninggalkan `output.apk` + `.fingerprint`

```java
if (!runner.run(stages)) return false;   // artefak lama dibiarkan
```

Build A yang gagal (mis. ECJ error dari kode user) meninggalkan `output.apk` dan
`.fingerprint` dari build sebelumnya. Build B kemudian melihat `hasPrevCache == true` dan
fingerprint cocok → `[cache] tidak ada perubahan -> APK sudah terbaru (skip)` → **tidak
membangun apa pun**. Yang dipasang adalah APK lama; sampah error terbawa antar build.

**Perbaikan:** saat `runner.run()` gagal, buang `output.apk`, `.fingerprint`, `classes/`,
`.stage-cache`, `gen/`, `R.jar`, `.rjar-fp`. Sumber / resource TIDAK dibuang — lihat
"Sengaja dipertahankan" di bawah. `hasPrevCache` juga mensyaratkan `outApk.length() > 0`.

### Bug 5 - `aapt2 link -o resources.ap_` menulis langsung ke file tujuan

```java
lc.add("-o"); lc.add(ap_.getAbsolutePath());
```

Bila `aapt2` gagal di tengah (resource user bermasalah), `resources.ap_` bisa tertinggal
dalam keadaan terpotong, sementara `.resap-fp` lama masih ada → build berikutnya
"link reuse" memakai arsc yang tidak lengkap → id tak ditemukan saat runtime.

**Perbaikan:** link ke `resources.ap_.new-<nanoTime>`, verifikasi ada & tidak kosong, baru
`Files.move(..., REPLACE_EXISTING)` (atomic). Bila link gagal: buang `resources.ap_` +
`.resap-fp` supaya build berikutnya pasti link ulang.

### Bug 6 - kompilasi resource user gagal meninggalkan `user.zip` + `.userfp` lama

Saat `aapt2 compile user` gagal, hanya `tmpZip` yang dibuang; `user.zip` dan `.userfp` dari
build lama dibiarkan. Bila fingerprint sempat sama (mtime FUSE basi), `user.zip` basi
ter-link kembali → id/string baru tidak masuk `resources.arsc`.

**Perbaikan:** keduanya dibuang saat compile gagal maupun saat zip hasil kosong.

### Bug 7 - pemasangan APK lewat installer sistem dengan `versionCode` tetap

`EditorActivity.installApk()` memakai `Intent.ACTION_VIEW` (installer sistem). Karena
`module.toml` proyek user hampir selalu `versionCode = 1` dan tidak pernah naik, installer
menganggap paket "sudah terpasang" dan **menolak mengganti** — dialog Update tampak
berhasil, APK lama tetap jalan. Inilah satu-satunya penyebab yang tersisa setelah seluruh
isi APK terbukti konsisten.

**Perbaikan:** coba `pm install -r -d <apk>` lebih dulu (`-r` replace, `-d` izinkan
downgrade) lewat `ProcessBuilder`, log exit code-nya; installer sistem hanya dipakai sebagai
fallback bila `pm` gagal.

### Sengaja dipertahankan saat build gagal

`flat/user.zip`, `.userfp`, `resources.ap_`, `.resap-fp` **tidak** dibuang. Keempatnya
dihasilkan stage `resource` yang **selesai sebelum** `compileJava`, jadi isinya sah; ECJ
error adalah masalah kode Java user dan tidak menajiskan resource. Membuangnya hanya
memaksa `aapt2 link` (~9-11 s) berjalan lagi tanpa manfaat.

### Kesalahan analisis yang perlu dihindari

Parser ARSC/XML buatan sendiri **mudah salah** dan menghasilkan kesimpulan palsu:
ResStringPool mencampur panjang string UTF-8 (1/2 byte, diikuti byte NUL) dengan UTF-16, dan
`TypeChunk.entries` bisa lebih kecil dari indeks id tertinggi. Beberapa kali parser tulisan
sendiri melaporkan "string terpotong" dan "id tidak ada di arsc" padahal APK sehat.

Gunakan `aapt2` — yang memang dipakai pipeline:

```sh
aapt2 dump resources <apk> | grep 'id/list_songs'
aapt2 dump xmltree  <apk> --file res/layout/activity_main.xml
```

Hasil benar untuk kasus NxMusic:

```
R.id.list_songs = 0x7f080114   (R.java / R.jar / dex)
id/list_songs   = 0x7f080114   (resources.arsc)
ListView @id    = 0x7f080114   (res/layout/activity_main.xml di dalam APK)
```

Ketiganya cocok ⇒ APK konsisten. Bila aplikasi tetap crash setelah itu, penyebabnya bukan
resource/id melainkan tahap **pemasangan** (lihat Bug 7).

### Verifikasi

- `javac` atas seluruh sumber host: 0 error dari `BuildPipeline.java` dan `EditorActivity.java`
  (error tersisa hanya `androidx`/`com.ikram.system` yang memang tidak ada di classpath host).
- Pemeriksaan APK hasil build NxMusic: `R.java`, `R.jar`, `resources.arsc`, `xmltree`
  layout, dan `classes*.dex` semuanya sepakat pada nilai id yang sama.
- Belum diverifikasi di perangkat: perilaku `pm install -r` (Bug 7) perlu dites dengan
  memasang APK hasil build ulang.

## Catatan Sesi (2026-09-18) — Perbaikan "cache hantu": build sukses tapi APK basi / FC

Gejala: build **sukses**, APK dipasang, tapi aplikasi FC pada UI yang baru diubah.
**Hapus seluruh `app/build/` lalu build ulang → aman.** Ciri khas bug cache: ada stage yang
di-SKIP padahal inputnya berubah, sehingga artefak lama (dex/class/arsc/layout) ikut ke APK.
Bukan bug layout user — layout & id sudah terbukti konsisten (`aapt2 dump` vs `R.java`).

### Akar tunggal: memo hash ber-kunci `size:mtime`

`cachedHash()` menyimpan `path -> (size+mtime) : md5`. Bila `size+mtime` tak berubah, md5 LAMA
dikembalikan tanpa baca ulang. Asumsi "penulis selalu memajukan mtime" **runtuh di `/sdcard`
(FUSE) dan pada ART**: mtime bisa TIDAK maju walau isi berubah (edit <1 dtk, editor yang
mempertahankan mtime, tulis ulang panjang sama). Akibat: fingerprint "tak berubah" → stage
di-SKIP → APK basi. `hashOf()` punya cacat sejenis: bila `md5` gagal baca, fallback-nya
`size+mtime` (dua file beda isi, panjang+mtime sama → bertabrakan → cache basi dipakai).

### 5 perbaikan (semua di `BuildPipeline.java`)

| # | Lokasi | Masalah | Perbaikan |
|---|--------|---------|-----------|
| 1 | `cachedHash` | memo `size:mtime` dikembalikan untuk artefak build yang ditulis ulang in-place → hash basi → stage SKIP | Artefak di bawah `work` (kecuali `.class`) → **md5 segar** (abaikan memo). `.class` dikecualikan: ratusan file (~12MB), md5 segar ~2.5s/pass ×4 pass = 10s regresi; dan itu satu-satunya artefak yang ECJ jamin mtime-nya maju saat tulis ulang. |
| 2 | `hashOf` | fallback `size+mtime` bisa bertabrakan | fallback → sentinel unik `!unreadable:<len>:<nanoTime>` → **selalu** memaksa cache invalid, tak pernah cocok dgn nilai tersimpan |
| 3 | `build()` top-level `fp` | `versionCode`/`versionName`/`minSdk`/`targetSdk`/`dexMode`/`packageName` TIDAK ikut fingerprint → ubah config saja → `fp` sama → `return true` (skip total) → APK lama (versionCode lama) tetap dipublikasikan | tambah `cfgFp` ke `fp` |
| 4 | `dexLibraries()` stale-delete | hanya buang dex `dexNumber > appDexCount`; sisa dex bernomor KECIL (lib menyusut, tertinggal krn `clearDexDir` gagal senyap di FUSE) lolos → dipaket → **class hantu → FC** | buang **semua** `classes*.dex` di luar rentang `1..appDexCount` (+ log bila `delete()` gagal) |
| 5 | `collectLibraryRes()` marker | marker `.extracted` berisi `"1"` buta; ekstraksi AAR terputus → `res/` terpotong tapi marker ada → cacat dipercaya SELAMANYA → resource library hilang/basi | marker berisi sentinel `jumlahFile:totalByte`; tak cocok → ekstrak ulang |

### Biaya (diukur di perangkat, NxMusic)

- `cachedHash` fresh md5 utk 77 dex (17.8MB) ≈ 0.6s/pass; R.jar/ap_/apk masing-masing 20-75ms.
- Total regresi estimasi **~1.5-2s** dari build ~56s (≈3.5%). Diterima demi APK benar.
- `.class` tetap memo → tak ada regresi 10s.

### Verifikasi

- `javac` atas `BuildPipeline.java`: **81 error** — identik dengan baseline `BuildPipeline.java.bak-cachefix`
  (semua `cannot find symbol`/`package does not exist`, murni karena `androidx`/`org.objectweb.asm`/
  `databinding` tidak ada di classpath host). **0 error baru.**
- Self-check: `CacheFixCheck.java` (`javac CacheFixCheck.java && java -ea CacheFixCheck`) → **PASS**.
  Menguji: (1) artefak build → fresh-hash sedangkan `.class` & sumber → memo, (2) `hashOf`
  fallback sentinel tak bertabrakan, (5) `resSentinel` berubah saat ekstraksi terpotong.
- Seimbang braces/parens vs baseline (delta identik).
- **Belum diverifikasi di perangkat**: build inkremental nyata lewat CodeAssist (edit layout →
  Build tanpa clean → cek APK). Perlu dites; backup `BuildPipeline.java.bak-cachefix` tersedia
  untuk revert.

---

## Catatan Sesi (2026-09-18 malam) — MEKANISME TERBALIK: id null saat TAMBAH maupun HAPUS resource

Gejala yang dilaporkan pengguna, bertahan lintas build:

> "setiap menambahkan id baru (TextView/layout) di build kedua pasti FC — NullPointerException
> di `findViewById`. Sekarang setelah menghapus id, FC lagi. Jadi terbalik."

Ternyata **dua gejala, satu akar**. Tambah id → FC. Hapus id → FC. Dua-duanya `null`.

### Akar: Java meng-INLINE `static final int R.id.X` sebagai LITERAL

`R.id.txt_home_subtitle` bukan referensi field saat runtime. Ia `public static final int`,
dan ECJ menanam **nilai mentahnya** ke bytecode setiap `.class` yang memakainya:

```java
// MainActivity.java
txtHomeSubtitle = findViewById(R.id.txt_home_subtitle);
// jadi, di MainActivity.class:
txtHomeSubtitle = findViewById(2131231268);   // literal, dibekukan saat compile
```

Karena itu, **tabel id bergeser = semua literal di `.class` jadi usang.**

- Tambah resource → id sesudahnya **naik** (`0x226` → `0x228`).
- Hapus resource → id sesudahnya **turun** (`0x226` → `0x224`).

Arah tidak penting. Yang penting: **literal lama di `.class` ≠ id di `resources.arsc`**.

### Bug 1 (FATAL, inti) — `fullEcj` diputuskan dari "APK lama ada", bukan "apakah R berubah"

`BuildPipeline.java`, `build()`:

```java
final boolean fullEcj = !(cfg.dexMode.equals("auto") ? hasPrevCache : ...);
// hasPrevCache = outApk.exists() && outApk.length() > 0 && fpFile.exists()
```

Begitu ada APK sebelumnya → `fullEcj = false` → ECJ **inkremental** → hanya `.java` yang
**berubah** di-recompile (`changedRel`).

Masalahnya: pemakai `R.id` (mis. `MainActivity.java`) **tidak berubah** — yang berubah hanya
`res/`. Jadi ia tak masuk `changedRel` → `.class`-nya **tak ditulis ulang** → literal id lama
tetap tertanam → `findViewById(0x226)` sementara layout punya `0x224` → **null → NPE**.

Sistem inkremental hanya tahu `.java` mana yang berubah. Ia **tidak tahu** bahwa *nilai
konstanta yang sudah di-inline* ke `.class` jadi usang. Perubahan `R` tak pernah jadi sinyal.

**Perbaikan** (`stageCompile`, setelah `rJarCached` dihitung):

```java
boolean rChanged = !rJarCached;          // R.java ATAU resources.ap_ beda dari build lalu
if (rChanged) fullEcj = true;            // -> ECJ compile SEMUA sumber, bukan hanya yang berubah
```

`rFp` (kunci `rJarCached`) isinya **hanya resource**:
`lastResFp # jumlah R.java # hash tiap R.java # hash resources.ap_`.
Tak ada satu pun `.java` dari `src/main/java` — jadi **ubah file `.java` biasa TIDAK memicu
full recompile**. Hanya sentuh `res/` yang memicu. Trade-off sengaja: **compile lebih lama >
APK salah**.

### Bug 2 — `resource.outputFp()` tidak meng-hash `work/gen/`

`stageCompile` **membaca `work/gen/`** (R.java + `*Binding.java`) untuk membentuk `R.jar`.
Tapi fingerprint output stage `resource` hanya memuat `resources.ap_` + `R.jar` — komentar
di kode bahkan menulis "JANGAN hash gen/". Akibatnya `gen/` bisa basi tanpa terdeteksi →
stage dianggap UPTODATE → `R.jar` lama → id baru hilang.

**Perbaikan**: `outputFp()` menyertakan hash `work/gen/**` (rekursif, terurut).

### Bug 3 — `resource.inputFp()` memakai rumus berbeda dari `.fingerprint`

`inputFp` = `fingerprint(res,manifest) + lastResFp`, sedangkan kunci skip-total `build()`
(hanya) `fingerprint(res,manifest)`. Dua kebenaran untuk hal yang sama → stage bisa dianggap
"cocok" padahal `gen/` basi.

**Perbaikan**: hapus `+ lastResFp` dari `inputFp`. Sekarang konsisten.

### Bug 4 — link-reuse tak ragu meski `gen/` tertinggal

`compileResources` early-return saat `fpRes` cocok, hanya memeriksa `hasAnyRJava()` (ada/tidaknya
R.java) — bukan apakah ia **lebih tua** dari `resources.ap_`.

**Perbaikan**: jaring kedua — paksa link ulang bila `newestMtime(gen) < resources.ap_.lastModified()`.

### Bug 5 — `generateR()` "sukses" tanpa menulis apa pun

```java
private boolean generateR(File rJavaDir) { rJavaDir.mkdirs(); return true; }   // LAMA
```

Dipanggil saat `resDir` bukan direktori. Return `true` tanpa R.java → `stageCompile` jatuh ke
`R.jar` lama → id baru hilang, **tanpa error apa pun** (jalur senyap ke FC).

**Perbaikan**: bersihkan `gen/`, tulis `R.java` kosong yang valid, return `false` bila gagal.

### Bug 6 — file non-res ikut masuk APK dan menambah ketidakstabilan cache

`aapt2 compile --dir res/` menelan **semua** file, apa pun ekstensinya. File sampah seperti
`activity_main.xml.bak-header` jadi entri `res/layout/activity_main.xml.xml.bak-header` di APK
(dead weight, dan ikut ke fingerprint).

**Perbaikan**: `isResFile()` (allowlist ekstensi) + `stageResDir()` — res disalin ke
`work/res-stage` tanpa file non-res, aapt2 compile dari sana.

### Bug 7 — auto-install lewat `pm` (shell script) tidak meneruskan stdin

Setelah build sukses, install otomatis gagal berlapis:

1. `pm install <path>` → `Can't open file`. Penyebab: `/storage/emulated/0` dan
   `/data/user/0/<pkg>/cache` adalah konteks SELinux (`fuse` / `app_data_file`) yang **tidak
   boleh dibaca `system_server`**.
2. `pm install -S <size>` + APK dialirkan ke stdin → `EPIPE (Broken pipe)`.
   Penyebab: `/system/bin/pm` **bukan binary** — ia shell script tipis yang memanggil
   `cmd package ...`, dan script itu **tidak meneruskan stdin**.

**Perbaikan**:
- Salin APK ke lokasi yang bisa ditulis: `getCacheDir()` → `getFilesDir()` → `/data/local/tmp` (fallback).
- Jalur 1: `cmd package install -r -d <path>`.
- Jalur 2 (tak menyentuh path sama sekali): **`/system/bin/cmd package install -r -d -S <size>`**
  + APK dialirkan lewat stdin. Pakai `cmd`, **bukan** `pm` — penerus stdin yang sebenarnya.

### Pola umum (pelajaran)

- **`static final int` = literal yang dibekukan.** Setiap cache yang cuma melihat "file mana
  yang berubah" akan salah untuk `.class` yang memakai konstanta yang berubah tanpa file-nya
  ikut berubah.
- **Dua rumus fingerprint untuk hal yang sama = bug.** (`inputFp` vs `.fingerprint`.)
- **Output stage harus mencakup SEMUA yang dibaca stage berikutnya.** (`gen/` dibaca
  `stageCompile`, jadi wajib ada di `outputFp`.)
- **Komentar "JANGAN hash X" layak dicurigai.** Asumsi di baliknya bisa sudah usang.

### Verifikasi

- `javac` atas seluruh `app/src/main/java`: **100 error**, **identik dengan backup**
  (`android-code.bak-20260918-112136`). Semua `cannot find symbol`/`package does not exist`
  (androidx/asm/databinding tak ada di classpath host). **0 error baru.**
- Self-check runnable: `IndInline.java` (`javac IndInline.java && java -ea IndInline`) →
  membuktikan literal id lama tertanam di `.class` → `findViewById` null.
- Simulasi fingerprint (`Sim2`): rumus `outputFp` lama **cocok walau `gen/` basi** (bug),
  rumus baru **mendeteksi** (fix).
- Filter res: `ResFilterCheck` → 8 file sah diterima, 10 file sampah ditolak. PASS.
- **Bukti di perangkat** (`Nx Music 2/app/build/console.log`, build 12:25, setelah hapus
  `home_bar_4`):

  ```
  [R] R berubah -> paksa recompile SEMUA sumber (inline R.id)
  [R] R.jar: 532 class dari 28 R.java
  [R] ekstrak 532 class R ke classes/ (sebelum ECJ)
    [tool] ECJ OK: 53 unit(s)
  [selesai] SUKSES
  ```

  Fix cache **bekerja**: recompile penuh terpicu otomatis oleh perubahan R.

- **Belum terverifikasi**: install APK (fix `cmd package install -S` belum diuji di
  perangkat karena `android-code` harus di-rebuild + install ulang dulu). Kalau `pm -S`
  masih gagal, jalur alternatif: Shizuku / install manual.

---

# Catatan Sesi (2026-09-23) — Dukungan Kotlin (compile .kt) di android-code

## Hasil akhir

android-code kini bisa **compile kode Kotlin** jadi APK, in-process, via compiler K2 (kotlinc 2.4.20-dev) yang di-dex dari APK CodeAssist. Proyek uji: `/sdcard/Ikram/kotlinc` (MainActivity.kt) — build SUKSES → APK 6.4 MB.

## Asset toolchain baru (app/src/main/assets/kotlin/)

| Asset | Isi | Peran |
|---|---|---|
| `kotlin-compiler.dex.zip` (68 MB) | `KotlincMain` + dex compiler K2 2.4.20 (dari APK CodeAssist: classes.dex + classes2..20) + resource META-INF (compiler.version, extensions XML, services) | compiler + entry non-exit |
| `kotlin-stdlib.jar` (1.8 MB) | stdlib JAR | resource `.kotlin_builtins` (wajib dibaca saat init) |
| `kotlin-stdlib.dex.zip` | stdlib ter-dex | kelas kotlin.* runtime utk classloader |
| `kotlin-reflect.dex.zip` (0.9 MB) | kotlin-reflect 2.4.0 (Maven) ter-dex | compiler butuh refleksi penuh utk baca properti argumen |

`ToolchainManager` ver `v11-kotlin` → ekstrak ulang toolchain (hapus cache lama).

## Cara compile Kotlin (pipeline)

```
.kt (src/main/{java,kotlin}) 
  → [stage compileKotlin] K2 via DexClassLoader parent=null 
      classpath = android.jar + stdlib.jar + core-stubs + lib jars + R.jar (compile-only)
      output = work/kotlin-classes/
  → [stage compileJava] ECJ, classpath + kotlin-classes (Java bisa lihat Kotlin)
  → [stage dex] kotlin-classes ikut di-dex (jar kedua / bucket per-root)
  → package → sign
```

- `KotlincMain` (di dex zip): panggil `K2JVMCompiler.exec(MessageCollector, Services, args)` via refleksi — non-exit, tanpa jansi (API 2.4 pakai `exec(MessageCollector, Services, CommonCompilerArguments)`).
- Argumen mirip CodeAssist: `-no-stdlib -no-reflect -no-jdk`, `jvmTarget=17`, classpath boot = android.jar, `kotlinc.art.home` = META-INF resources (PathUtil di-patch lewat properti).

## Kunci agar in-process BERHASIL (pelajaran mahal)

1. **`DexClassLoader` parent = `null`** — BUKAN app host classloader. App host sudah set `kotlin.jvm.internal.Reflection.factory` (stdlib 1.6.10 "no reflection") → kotlin-reflect penuh tak bisa re-register → error `Kotlin reflection is not available`. Parent null = reflect resolve bersih.
2. **stdlib JAR ikut di path classloader** — resource `.kotlin_builtins` (kotlin.Any dkk) hanya bisa dibaca dari JAR, bukan dex. Tanpa ini: `Built-in class kotlin.Any is not found`.
3. **kotlin-reflect penuh** (2.4.0) wajib — dex APK CodeAssist cuma memuat reflect parsial (utk app-nya), error `Java field should be present for property fragments`.
4. **4 asset digabung di satu classloader**: `compiler.dex.zip : reflect.dex.zip : stdlib.dex.zip : stdlib.jar`.
5. `kotlinc.art.home` → dir hasil ekstrak META-INF dari compiler dex zip (dibaca `PathUtil`/`KotlinCompilerVersion`).
6. Fallback: kalau in-process gagal (ToolRuntimeError) → fork dalvikvm (path sama 4 asset).

## Bug yang diperbaiki sesi ini

### Bug 1 — `compileKotlin GAGAL (exit=2)` "Kotlin reflection is not available"
In-process pertama pakai `DexClassLoader` parent = app host → Reflection.factory rusak. **Fix**: parent=null + reflect dex + stdlib jar di path.

### Bug 2 — `dex hilang setelah archive: ../kotlin-classes/...` (bucket-lite)
Class Kotlin di-relativize ke `classesDir` → rel path `../kotlin-classes/...` → path dex cache invalid. **Fix** (`dexAppBuckets`): rel path per-root — class di `kotlin-classes/` relatif ke root-nya sendiri.

### Bug 3 — stdlib tak ter-package ke APK → runtime NoClassDefFoundError
**Fix**: `BuildPipeline.build()` deteksi `.kt` → `kotlin-stdlib.jar` otomatis masuk `libJars` (→ dex library + classpath).

### Bug 4 — R.jar untuk Kotlin
Stage `compileKotlin` jalan SEBELUM `compileJava` (yang membuat R.jar). **Fix**: compileKotlin bangun R.jar dari `gen/` dulu (via `RBytecode.writeJar`) bila belum ada.

## Verifikasi

- javac host semua kelas: ✅ exit=0
- `LoaderTest` (DexClassLoader parent=null, 4 asset) → `KOTLINC_EXIT=0`, `MainActivity.class` ✅
- Build proyek `/sdcard/Ikram/kotlinc` via android-code: 6 stage sukses, APK 6.4 MB ✅
- Install via pm gagal (SELinux `avc: Permission denied` utk `/data/user/0/.../install.apk`) — bukan bug build; APK valid.

## Kendala yang TERSISA

1. **Crash runtime**: `No package ID 7f found for resource ID 0x7f030350` (resource library `prefixTextColor` dari androidx.activity). Dugaan: ID resource library yang tertanam di .class app ≠ tabel resource final APK (R.jar app vs library tidak sinkron saat link/reuse). **BELUM teratasi** — perlu dump `resources.ap_`/`output.apk` dgn aapt2 (so belum sempat di-uji).
2. **Warning metadata R8** `kotlin.Metadata 2.4 vs max 2.3` — non-fatal (dex tetap valid), R8 pakai kotlin-metadata-jvm lama; bisa dihilangkan dgn upgrade r8.
3. **Install lewat pm** gagal SELinux — APK harus di-install manual (installer sistem / salin ke /data/local/tmp).

## Catatan implementasi

- `KotlincMain.java` (compile host JVM, dex jadi entry): argumen `[0]=outDir [1]=classpath [2..]=sources`; properti `kotlinc.jvmTarget/noJdk/noStdlib/noReflect/pluginClasspaths/pluginOptions`; output ke System.out (OUTPUT msgs) & System.err (error/warning).
- InProcessTools `kotlinc(...)`: 4 asset classloader + `kotlinHome()` (extract META-INF), redirect console.
- BuildPipeline `compileKotlin(...)`: coba in-process → fallback fork (4 asset, `dalvikvm64`).
- `ProjectConfig.kotlinDir()` = `src/main/kotlin`.
- Metadata warning hanya muncul sekali per class (R8); tidak menghalangi dex.


---

# Catatan Sesi (2026-09-25) - Kotlin ABI (ala CodeAssist KotlinAbi) + deteksi inline

## Hasil

`KotlinIncremental` kini menyimpan **ABI per-class** (bukan cuma hash sumber), dan
`BuildPipeline` memakainya untuk memutuskan FULL vs commit pada compile Kotlin incremental.
Ini meniru `IncrementalKotlinCompiler` + `KotlinAbi.kt` CodeAssist.

## File

| File | Perubahan |
|---|---|
| `build/KotlinAbi.java` | **BARU** - snapshot ABI via ASM: header class + field/method non-private + `@kotlin.Metadata` raw. Deteksi `inline` via `kotlin-metadata-jvm` -> hash PENUH file. |
| `build/KotlinIncremental.java` | State += `a\t<rel>\t<abi>`. Method baru: `abiOf`, `abiChanged`, `record(kt,outDir)`. |
| `build/BuildPipeline.java` | `compileKotlin` overload dgn `binDir` (staging). Stage incremental: compile dirty -> staging -> cek ABI -> beda=FULL, sama=commit. Helper `copyTreeInto`. |
| `app/module.toml` | dep baru `org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0`. |

Backup: `KotlinIncremental.java.bak-before-abi`, `BuildPipeline.java.bak-before-abi`.

## Mengapa ABI

Java meng-INLINE `static final int` (lihat bug R.id), dan Kotlin meng-INLINE body fungsi
`inline`. Keduanya membuat perubahan yang tak terlihat di "file mana yang berubah".
ABI memberi sinyal kedua: bila ABI output class yang di-recompile SAMA seperti sebelumnya,
dependent tak terpengaruh -> aman commit (hemat). Bila ABI berubah -> FULL.

## Deteksi inline: 4 bug yang ditemukan saat mengembangkan

Semua terungkap dengan menguji di ART nyata (dalvikvm64), bukan tebakan:

1. **`new kotlin.Metadata(...)` tak bisa dari Java** (annotation class). Solusi: `Proxy`
   implement `kotlin.Metadata`. CodeAssist bisa karena Kotlin.
2. **`visit()` ASM memfilter tipe.** Kode awal hanya simpan `String`/`int[]`, sehingga
   `k` dan `xi` (datang sbg **Integer**) hilang -> `InconsistentKotlinMetadataException`
   / `InvalidProtocolBufferException`. Fix: simpan semua nilai non-null.
3. **`mv`/`bv` lewat `visit()` sbg `int[]`, `d1`/`d2` lewat `visitArray()` per-elemen.**
   Dua jalur berbeda; keduanya harus ditangani. (`bv` sering absen -> `new int[0]`.)
4. **`META-INF/services/kotlin.metadata.internal.extensions.MetadataExtensions` wajib ada.**
   `KotlinClassMetadata` memakai ServiceLoader; tanpa entri itu:
   `No MetadataExtensions instances found in the classpath`. D8 TIDAK membawa META-INF -
   jadi bila `KotlinAbi` suatu saat di-dex ke dex-zip, resource ini harus disertakan
   (seperti `ecj.dex.zip` menyertakan `messages.properties`). Di host ia in-process,
   jar ada di classpath -> ServiceLoader OK.

## Verifikasi (di ART, dalvikvm64)

```
KotlinAbi self-check PASS
metadata OK: MainActivity.class inline=false
inline terdeteksi: ArraysKt__ArraysKt.class
AbiSemantics PASS (deterministik / stabil / beda class -> beda ABI)
ECJ OK - KotlinAbi.java, KotlinIncremental.java, BuildPipeline.java (0 error baru)
```

Fixture JSON test: `/sdcard/Ikram/kotlinc` (build/kotlin-classes) + `kotlin-stdlib.jar`.

## Belum diverifikasi

- Build nyata lewat CodeAssist di perangkat (ABI gate di tengah build).
- Perilaku saat `KotlinAbi` gagal baca metadata -> `hashFull` (fallback konservatif).

# Catatan Sesi (2026-10-13) — Fix cascade resource → Kotlin+Java recompile

## Gejala

Edit resource apa pun (ubah nilai string, warna, isi layout — tanpa tambah/hapus id)
memicu recompile **penuh** Kotlin DAN Java, walau tabel id `R` tak bergeser. Satu edit
kecil jadi cascade 3 tahap (`resource` → `compileKotlin` FULL → `compileJava` FULL → `dex`).

## Akar masalah

Fingerprint mencampur dua hal berbeda:

- **"tabel id R berubah"** (tambah/hapus resource) → **wajib** recompile, karena Java/Kotlin
  meng-INLINE `R.id.X` jadi literal di bytecode. `.class` basi → `findViewById` id lama → NPE.
- **"payload resource berubah"** (edit nilai string/layout) → **tak boleh** recompile, tabel id sama.

`resources.ap_` memuat SELURUH payload resource (teks/gambar/`.arsc`), jadi md5-nya berubah
tiap edit nilai. Tapi `resources.ap_` dipakai sebagai pemicu jenis pertama di dua tempat:

1. `rFpB` (fingerprint `R.jar`) menyertakan `cachedHash(resources.ap_)` → `.rjar-fp` tak cocok
   → `rJarCached=false` → `rChanged=true` → paksa `fullEcj` (SEMUA `.java`).
2. Fingerprint `compileKotlin` menyertakan `resap:` (md5 `resources.ap_`).

Lalu output `.class` Kotlin berubah → fingerprint `compileJava` menyertakan `kc:` (hash tiap
`.class` Kotlin) → Java FULL lagi.

## Yang sesungguhnya = tabel id

`R.java` keluaran aapt2 **hanya** berisi `public static final int name=0xID;` — tak ada nilai
string/warna/teks. Jadi:

```
R.java        = name → id            (stabil antar edit nilai)
R.jar         = bytecode dari R.java (stabil antar edit nilai)
resources.ap_ = R.java(is) + SELURUH payload (berubah tiap edit nilai)
```

Tabel id yang sah = isi `R.java`, dan itu sudah di-hash di `rFpB`. `resources.ap_` hanya
cadangan untuk kasus `gen/` kosong (build gagal).

Ini juga cara CodeAssist: `AndroidKotlinCompileTask.inputs` = kotlinSources + javaSources +
classpath + level; `classpath` memuat `layout.rJar`, **tidak** `resources.ap_`. R.jar =
bytecode R.java → content-stable → Kotlin & Java skip saat edit nilai.

## Perbaikan (2 edit)

| Lokasi | Sebelum | Sesudah |
|---|---|---|
| `compileKotlin` fp (L~444-446) | `if (apFp.isFile()) sb.append("resap:")...` | baris dibuang (R.jar sudah mewakili tabel id) |
| `rFpB` / `R.jar` fp (L~858) | `if (apForFp.isFile()) rFpB.append('#')...` | `if (rJavaFiles.isEmpty() && apForFp.isFile()) ...` |

Hasil: edit nilai resource → `R.jar` identik → `rJarCached=true`, `rChanged=false` (tak paksa
`fullEcj`); fp Kotlin identik → `compileKotlin` UPTODATE; `.class` Kotlin tak berubah → fp
`compileJava` identik → `compileJava` UPTODATE. Sisa: `dex`/`package`/`sign`.

Tambah/hapus id → `R.java` berubah → `lastResFp` + `cachedHash(R.java)` berubah → R.jar
dibangun ulang → Kotlin+Java recompile (benar, inline `R.id`).

## Mengapa tidak basi

Jaring pengaman R.jar basi tetap utuh:

- `rFpB` masih memuat `lastResFp` (md5 libRes+userRes+manifest+cfg) + hash isi tiap `R.java`.
- Id bergeser ⟹ aapt2 link ulang ⟹ `R.java` baru ⟹ hash beda ⟹ R.jar dibangun ulang.
- Guard `rJavaFiles.isEmpty()` mengaktifkan kembali cek `resources.ap_` saat `gen/` kosong.
- Jalur link-reuse punya 2 cek sendiri: `gen/R.java` hilang, atau `gen/R.java` lebih tua dari
  `resources.ap_` → paksa link ulang.

Risiko teoretis (id anonim di `arsc` tanpa padanan `R.java`) tak berdampak: id itu tak bisa
di-`R.id.X` → tak pernah di-inline ke bytecode.

## File

| File | Perubahan |
|---|---|
| `build/BuildPipeline.java` | Buang `resap:` dari fp `compileKotlin`; guard `resources.ap_` di `rFpB` dgn `rJavaFiles.isEmpty()`. |

Backup: `build/.bak-BuildPipeline-20261013-231002.java`.

## Belum diverifikasi

- Build nyata lewat CodeAssist di perangkat. Uji: edit 1 nilai string → Build → log harus
  `compileKotlin`/`compileJava` **UPTODATE**; tambah 1 `@+id` → Build → keduanya **RUNNING**.

# Catatan Sesi (2026-10-14) — Fix AKAR: R.jar non-deterministik → cascade resource→Kotlin+Java

Entri 2026-10-13 di atas **belum menuntaskan** masalah. Log build nyata di
`/sdcard/Ikram/Nx Music 2/app/build/console.log` (build ke-2, hanya ubah 1 teks string)
menunjukkan cascade MASIH terjadi.

## Gejala (log nyata, build 2, edit 1 nilai string)

```
[1/6] resource         RUNNING    14355 ms
[2/6] compileKotlin    RUNNING    36396 ms    <- FULL recompile
[3/6] compileJava      RUNNING    13143 ms    <- FULL recompile
[4/6] dex              UPTODATE    1294 ms
[5/6] package          RUNNING     5079 ms
[6/6] sign             RUNNING     3379 ms
TOTAL: 76624 ms
```

Baris pemicu di log:

```
[!] [kotlin] R.jar lebih tua dari resources.ap_ -> bangun ulang
[R] R berubah -> paksa recompile SEMUA sumber (inline R.id)
```

## Akar masalah (BERBEDA dari diagnosa 2026-10-13)

Fix 2026-10-13 menyasar *fingerprint stage* (`inputFp`/`rFpB`). Tapi pemicu nyata ada di
**`run()`**, bukan fingerprint:

1. `RBytecode.writeJar` menulis ZIP entry via `new JarEntry(name)` **tanpa `setTime`**.
   `JarOutputStream.putNextEntry` men-set DOS time entry = `System.currentTimeMillis()`.
   ⟹ R.jar ditulis dua kali dari R.java yang SAMA menghasilkan **byte BERBEDA**.

   Bukti (`od` header lokal R.jar, field modtime = bytes 10-13):
   ```
   50 4b 03 04 14 00 08 08 08 00 e8 5e 4e 5d ...
                                 ^^^^^ modtime (variabel = waktu build)
   ```
   Extra field (`fe ca 00 00`) = Info-ZIP marker, **tanpa** waktu. Jadi hanya DOS time yang
   variabel, dan `setTime(fixed)` cukup.

2. `compileKotlin.run()` menilai R.jar basi lewat
   `rj.lastModified() < apKt.lastModified()` + md5 `resources.ap_`.
   Edit nilai string ⟹ `resources.ap_` ditulis ulang ⟹ mtime baru ⟹ R.jar "basi" ⟹
   dibangun ulang (3.4s) ⟹ md5 R.jar bergeser ⟹ ctx Kotlin (`rjarContent`) berubah ⟹
   Kotlin FULL.

3. `.class` Kotlin baru ⟹ fp `compileJava` (`kc:`) berubah ⟹ `rChanged=true` ⟹
   `[R] R berubah` ⟹ ECJ FULL.

Kedua jalur memakai **md5/hash R.jar** sebagai kunci. R.jar tak stabil byte-ke-byte ⟹
semua fingerprint yang memuatnya cascade.

## Prinsip (sama seperti CodeAssist)

`GenerateRJarTask.inputs` di CodeAssist = **hanya `rSources()` (R.java)** —
`AndroidTasks.kt:502-515`. Payload resource tak pernah masuk. R.java hanya berisi
`public static final int name = 0xID;` (stabil antar edit nilai). Maka R.jar **harus**
fungsi dari isi R.java saja, byte-deterministik.

## Perbaikan (2 file)

### 1. `build/RBytecode.java` — R.jar byte-deterministik

| Lokasi | Sebelum | Sesudah |
|---|---|---|
| L68 | `jos.putNextEntry(new JarEntry(name + ".class"));` | `jos.putNextEntry(newJosEntry(name + ".class"));` |
| L74 | `jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));` | `jos.putNextEntry(newJosEntry("META-INF/MANIFEST.MF"));` |
| baru | — | `newJosEntry()` + `FIXED_ZIP_TIME = 946684800000L` (2000-01-01 UTC) |

```java
private static JarEntry newJosEntry(String name) {
    JarEntry e = new JarEntry(name);
    e.setTime(FIXED_ZIP_TIME);   // 946684800000L = 2000-01-01 UTC
    return e;
}
```

Urutan entry sudah deterministik (`collectRFiles` men-sort by absolutePath). Sisa
non-determinisme hanya timestamp — ditutup oleh `setTime`.

### 2. `build/BuildPipeline.java` — cek basi R.jar pakai SIDIK ISI R.java

Jalur `compileKotlin.run()`: ganti `mtime/md5 resources.ap_` → `rJavaContentHash(gen)`.

```java
String rJavaNow = rJavaContentHash(rJavaDir);
boolean rebuildRjar = !rj.isFile();
if (!rebuildRjar) {
    String madeFrom = read(rjarRJavaFpFile());
    if (!rJavaNow.equals(madeFrom)) {           // isi R.java berubah -> rebuild
        log.problem("[kotlin] isi R.java berubah -> bangun ulang R.jar");
        rebuildRjar = true;
    }
}
```

Helper baru (dekat `rjarApFpFile()`):

```java
private String rJavaContentHash(File rJavaDir) { ... }        // relPath+isi tiap R.java, sorted
private File rjarRJavaFpFile() { return new File(work, ".rjar-rjava-fp"); }
```

`stageCompile` (jalur bangun R.jar utama) ikut tulis `.rjar-rjava-fp`. Dua jalur tolak R.jar
(mtime/Sinyal-2) ikut hapus `.rjar-rjava-fp` bersama `.rjar-ap-fp`.

## Hasil yang diharapkan

Edit nilai resource (string/warna/isi layout, tanpa tambah/hapus id):
- isi `gen/R.java` identik ⟹ `rJavaNow == madeFrom` ⟹ R.jar TIDAK dibangun ulang
- R.jar identik (byte-deterministik) ⟹ `rjarContent` stabil ⟹ `compileKotlin` UPTODATE
- `.class` Kotlin tak berubah ⟹ fp `compileJava` (`kc:`) stabil ⟹ `compileJava` UPTODATE
- Sisa: `dex`/`package`/`sign`

Tambah/hapus `@+id`: R.java berubah ⟹ `rJavaNow != madeFrom` ⟹ R.jar dibangun ulang
(benar, Java/Kotlin meng-inline `R.id` jadi literal bytecode) ⟹ Kotlin **dan** Java RUNNING.

## Hubungan dengan entri 2026-10-13

Entri 2026-10-13 (buang `resap:` dari fp Kotlin; guard `resources.ap_` di `rFpB`) **tetap
benar** dan dipertahankan sebagai jaring pengaman R.jar basi. Tapi ia **tidak cukup** karena
pemicu sesungguhnya ada di `run()` (mtime + md5 R.jar), dan R.jar sendiri tak stabil. Entri
ini menutup akar itu.

## File

| File | Perubahan |
|---|---|
| `build/RBytecode.java` | `newJosEntry()` + `FIXED_ZIP_TIME`; 2 `new JarEntry` → `newJosEntry`. |
| `build/BuildPipeline.java` | `compileKotlin.run()` cek basi via `rJavaContentHash`; helper `rJavaContentHash`/`rjarRJavaFpFile`; tulis `.rjar-rjava-fp` di `stageCompile`; hapus di 2 jalur tolak. |

Backup:
- `build/RBytecode.java.bak-detjar-20261014-035749`
- `/tmp/BP.*.bak` (BuildPipeline, sesi sementara)

## Belum diverifikasi

- Build nyata lewat CodeAssist di perangkat.
- Kompilasi ulang kode di lingkungan dev ini **gagal** (JDK `libjvm.so` error
  `posix_fallocate64: symbol not found`; ECJ-di-ART butuh `javax.lang.model` yang tak ada di
  ART). Cek sintaks hanya: brace seimbang relatif terhadap backup. **Build not verified.**
- Uji: edit 1 nilai string → Build → log harus `compileKotlin`/`compileJava` **UPTODATE**;
  tambah 1 `@+id` → Build → keduanya **RUNNING**.

---

## Catatan Sesi (2026-11-19) — Optimasi: fingerprint stage & hash library

Backup: `build/BuildPipeline.java.bak-opt-stage-20261119-135055`.

Latar: build incremental **Nx Music 2** (log konsol nyata, `dexMode=auto`, 71 library)
`[time] TOTAL: 27716 ms`, dengan hampir semua kerja di-skip:

| tahap | ms | catatan |
|---|---|---|
| compileKotlin | 7529 | 1 dari 33 file berubah (6941 ms) |
| dex | 6194 | 8 bucket `unchanged, reuse`; `dex-library: 1949 ms (71)` |
| compileJava | 5781 | **ECJ skip** ("tidak ada file berubah"), hanya 70 ms kerja nyata |
| resource | 2914 | link reuse |
| package | 2171 | up-to-date (skip) |
| sign | 119 | — |

Biangnya bukan compiler, tapi **fingerprint** (`fp[...]` + `fp-post[...]`):

- `fp[compileJava]` 2668 ms + `fp-post[compileJava]` 1591 ms = **4259 ms** menghitung hash 582
  `.class` (23 MB) padahal ECJ tidak jalan dan tak ada yang menulis `classes/`.
- `dex-library` 1949 ms padahal SETIAP barisnya `[lib-dex] cache` — `hashOf()` membaca ulang
  ke-71 jar (~30 MB) tiap build.
- Total hash murni ≈ 6.3 s dari 27.7 s (23%), mayoritas untuk membuktikan "tidak ada perubahan".

### Perubahan (semua di `BuildPipeline.java`)

1. **`outputFp` stage compileJava tidak menghitung `.class` bila dirnya tak tersentuh.**
   Field baru `stEcjRan` (di-set di 3 jalur `runEcj`) dan `artifactsTouched` (di-set bila ada
   penulis `classes/` di luar ECJ: ekstraksi R.class, hapus `.class` berubah, hapus `.class`
   yatim). Bila keduanya false → `readStageOutFp("compileJava")` membaca baris ke-2
   `.stage-cache/compileJava.fp` (satu file kecil) dan mengembalikannya.
   Hemat ~4.0 s.

2. **Kunci cache libdex pakai memo `size:mtime` (`libHash`), bukan `hashOf` segar.**
   Jar library ditulis SEKALI oleh `MavenResolver` di `filesDir/libraries` lalu tak pernah
   ditulis ulang in-place — asumsi memo valid di sini. Hemat ~1.9 s.
   Log baru `[time] lib-dex hash: <ms> (<n>)` untuk memantau.

3. Kedua memo dibersihkan di awal `build()` (`memoOutFp.clear()`, flag di-reset).

### Kenapa ini TIDAK melanggar ATURAN 2026-09-11 / 2026-09-18

- Hash `.class` **tetap** `hashOf` segar (tidak pernah dimemo). Yang dipangkas hanya
  **perhitungan atas direktori yang terbukti tidak ditulis** build ini — bukan memo basi.
- `artifactsTouched` menutup SEMUA penulis `classes/` di luar ECJ. Kalau ada penulis baru di
  masa depan, ia WAJIB men-set flag itu, atau cache bisa salah-skip (APK basi → FC).
- Catatan `.stage-cache` tidak ada (build pertama / cache dibuang) → `readStageOutFp` null →
  hitung seperti biasa. Tidak pernah salah-skip.
- Sumber proyek, `res/`, `gen/`, `R.jar`, `resources.ap_`, `dex/` tetap hash isi segar.

### Verifikasi

- **Compile: TIDAK diverifikasi** — tidak ada `javac` di device ini (`which javac` kosong;
  JDK di lingkungan proot gagal `libjvm.so`). Cek sintaks hanya brace/paren balance:
  `{}` 587/587, `()` 4318/4318 (baseline 578/578, 4262/4262 — delta dari kode baru).
- **Belum diukur di perangkat.** Perkiraan 27.7 s → ~22 s.
- Cara uji: build ulang Nx Music 2 tanpa clean, cek log:
  - `[time] fp[compileJava]` tetap ada tapi `fp-post[compileJava]` harus **~0 ms** dan
    **tidak ada lagi** baris `fp-detail[compileJava.out]` → patch 1 aktif.
  - `[time] lib-dex hash` muncul dengan angka kecil setelah build pertama → patch 2 aktif.
- Regresi yang harus dicek setelah patch 1: edit 1 `@+id` di layout → build → `compileJava`
  **RUNNING** dan APK memuat id baru (bukan UPTODATE). Ini jalur `artifactsTouched`.
