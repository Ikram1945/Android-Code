package com.ikram.apkbuild;

import java.io.File;

/**
 * Membuat proyek user baru berformat CodeAssist (meniru AndroidAppTemplate /
 * MaterialYouAppTemplate di CodeAssist):
 *   <root>/
 *     app/
 *       module.toml
 *       proguard-rules.pro
 *       src/main/java/<pkg>/MainActivity.java
 *       src/main/res/layout/activity_main.xml
 *       src/main/res/values/{strings,colors,themes}.xml
 *       src/main/res/values-night/themes.xml
 *       src/main/res/drawable/ic_launcher_{background,foreground}.xml
 *       src/main/res/mipmap[-anydpi-v26]/ic_launcher[_round].xml
 *       src/main/AndroidManifest.xml
 */
public class ProjectCreator {

    public ProjectCreator(android.content.Context ctx) {}

    public boolean create(File root, String name, String pkg,
                          String versionName, int versionCode,
                          int compileSdk, int minSdk, int targetSdk, int javaLevel) {
        return create(root, name, pkg, versionName, versionCode, compileSdk, minSdk, targetSdk, javaLevel,
                new java.util.ArrayList<>(), 0);
    }

    /** create() + daftar library (koordinat Maven) + pilihan template (0=Activity, 1=Material You). */
    public boolean create(File root, String name, String pkg,
                          String versionName, int versionCode,
                          int compileSdk, int minSdk, int targetSdk, int javaLevel,
                          java.util.List<String> libs, int template) {
        return create(root, name, pkg, versionName, versionCode, compileSdk, minSdk, targetSdk, javaLevel,
                "inprocess", "inprocess", 0, 0, libs, template);
    }

    public boolean create(File root, String name, String pkg,
                          String versionName, int versionCode,
                          int compileSdk, int minSdk, int targetSdk, int javaLevel,
                          String javaExec, String kotlinExec,
                          java.util.List<String> libs, int template) {
        return create(root, name, pkg, versionName, versionCode, compileSdk, minSdk, targetSdk, javaLevel,
                javaExec, kotlinExec, 0, 0, libs, template);
    }

    /** create() + exec compiler + heap worker Kotlin + dex merge forks + library + template. */
    public boolean create(File root, String name, String pkg,
                          String versionName, int versionCode,
                          int compileSdk, int minSdk, int targetSdk, int javaLevel,
                          String javaExec, String kotlinExec, int kotlinWorkerHeapMb, int dexMergeForks,
                          java.util.List<String> libs, int template) {
        if (root.exists()) { return false; }
        boolean material = template == 1;
        boolean compose = template == 2;
        File app = ProjectConfig.appDir(root);
        File src = ProjectConfig.javaDir(root);
        String pkgPath = pkg.replace('.', '/');
        File pkgDir = new File(src, pkgPath);
        pkgDir.mkdirs();
        File res = ProjectConfig.resDir(root);
        new File(res, "layout").mkdirs();
        new File(res, "values").mkdirs();

        // module.toml
        ProjectConfig cfg = new ProjectConfig();
        cfg.moduleToml = ProjectConfig.moduleTomlFile(root);
        cfg.name = "app";
        cfg.packageName = pkg;
        cfg.versionName = versionName;
        cfg.versionCode = versionCode;
        cfg.compileSdk = compileSdk;
        cfg.minSdk = minSdk;
        cfg.targetSdk = targetSdk;
        cfg.javaLevel = javaLevel;
        cfg.javaExec = javaExec;
        cfg.kotlinExec = kotlinExec;
        cfg.kotlinWorkerHeapMb = kotlinWorkerHeapMb;
        cfg.dexMergeForks = dexMergeForks;
        cfg.implementation = libs == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(libs);
        if (material && !cfg.implementation.contains("com.google.android.material:material:1.12.0")) {
            cfg.implementation.add("com.google.android.material:material:1.12.0");
        }
        // Compose: runtime di classpath adalah penanda yang memicu plugin compiler Compose di
        // BuildPipeline (hasComposeRuntime). activity-compose menyediakan setContent { }.
        if (compose) {
            for (String c : new String[]{
                    "androidx.core:core-ktx:1.13.1",
                    "androidx.activity:activity-compose:1.9.3",
                    // Varian -android: di sinilah class Compose betulan diterbitkan. Artifact
                    // flat (runtime:/ui:/material3:) hanyalah stub AAR berisi AndroidManifest
                    // saja -> classes.jar tak ada -> Composable.class tak terlihat di classpath
                    // -> deteksi plugin compiler Compose di BuildPipeline tak terpicu.
                    "androidx.compose.runtime:runtime-android:1.7.6",
                    "androidx.compose.ui:ui-android:1.7.6",
                    "androidx.compose.foundation:foundation-android:1.7.6",
                    "androidx.compose.material3:material3-android:1.3.1"}) {
                if (!cfg.implementation.contains(c)) cfg.implementation.add(c);
            }
        }
        cfg.save();

        // proguard-rules.pro (meniru AndroidTemplateSupport.PROGUARD_RULES_PRO)
        Util.writeText(new File(app, "proguard-rules.pro"),
                "# Add project-specific ProGuard/R8 keep rules here.\n"
                + "# These are applied on top of the bundled defaults (proguard-android-optimize.txt) when the\n"
                + "# build type has minifyEnabled = true.\n"
                + "#\n"
                + "# Keep a class that is referenced only by reflection / from XML, e.g.:\n"
                + "# -keep class com.example.SomeClass { *; }\n"
                + "#\n"
                + "# Preserve line numbers for readable crash stack traces, then hide the original file name:\n"
                + "# -keepattributes SourceFile,LineNumberTable\n"
                + "# -renamesourcefileattribute SourceFile\n");

        // launcher icon set (meniru AndroidAppAssets.launcherIconResFiles — vector, tanpa binary)
        String iconBackground =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                + "    android:width=\"108dp\" android:height=\"108dp\"\n"
                + "    android:viewportWidth=\"108\" android:viewportHeight=\"108\">\n"
                + "    <path android:fillColor=\"@color/ic_launcher_background\" android:pathData=\"M0,0h108v108h-108z\"/>\n"
                + "</vector>\n";
        String iconForeground =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                + "    android:width=\"108dp\" android:height=\"108dp\"\n"
                + "    android:viewportWidth=\"108\" android:viewportHeight=\"108\">\n"
                + "    <path android:strokeColor=\"#FFFFFF\" android:strokeWidth=\"2\" android:strokeLineCap=\"round\" android:pathData=\"M45,45L40,37\"/>\n"
                + "    <path android:strokeColor=\"#FFFFFF\" android:strokeWidth=\"2\" android:strokeLineCap=\"round\" android:pathData=\"M63,45L68,37\"/>\n"
                + "    <path android:fillColor=\"#FFFFFF\" android:pathData=\"M38,72L38,58A16,16 0 0 1 70,58L70,72Z\"/>\n"
                + "    <path android:fillColor=\"@color/ic_launcher_background\" android:pathData=\"M45,54a2,2 0 1 0 4,0a2,2 0 1 0 -4,0\"/>\n"
                + "    <path android:fillColor=\"@color/ic_launcher_background\" android:pathData=\"M59,54a2,2 0 1 0 4,0a2,2 0 1 0 -4,0\"/>\n"
                + "</vector>\n";
        String adaptiveIcon =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                + "    <background android:drawable=\"@drawable/ic_launcher_background\"/>\n"
                + "    <foreground android:drawable=\"@drawable/ic_launcher_foreground\"/>\n"
                + "</adaptive-icon>\n";
        String legacyIcon =
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<layer-list xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                + "    <item android:drawable=\"@drawable/ic_launcher_background\"/>\n"
                + "    <item android:drawable=\"@drawable/ic_launcher_foreground\"/>\n"
                + "</layer-list>\n";
        Util.writeText(new File(res, "drawable/ic_launcher_background.xml"), iconBackground);
        Util.writeText(new File(res, "drawable/ic_launcher_foreground.xml"), iconForeground);
        Util.writeText(new File(res, "mipmap-anydpi-v26/ic_launcher.xml"), adaptiveIcon);
        Util.writeText(new File(res, "mipmap-anydpi-v26/ic_launcher_round.xml"), adaptiveIcon);
        Util.writeText(new File(res, "mipmap/ic_launcher.xml"), legacyIcon);
        Util.writeText(new File(res, "mipmap/ic_launcher_round.xml"), legacyIcon);

        // strings
        Util.writeText(new File(res, "values/strings.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n"
                + "    <string name=\"app_name\">" + name + "</string>\n"
                + "    <string name=\"hello_world\">" + (material ? "Hello, Material You!" : "Hello, World!") + "</string>\n"
                + (material
                        ? "    <string name=\"fab_description\">Add</string>\n"
                        + "    <string name=\"fab_clicked\">FAB clicked</string>\n"
                        : "")
                + "</resources>\n");

        // colors
        Util.writeText(new File(res, "values/colors.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n"
                + "    <color name=\"primary\">" + (material ? "#FF6750A4" : "#FF6200EE") + "</color>\n"
                + "    <color name=\"on_primary\">#FFFFFFFF</color>\n"
                + "    <color name=\"ic_launcher_background\">#3DDC84</color>\n"
                + "</resources>\n");

        // themes
        if (material) {
            // Theme.Material3.DynamicColors.DayNight: dynamic colour di Android 12+, baseline M3 di bawahnya.
            Util.writeText(new File(res, "values/themes.xml"),
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n"
                    + "    <style name=\"Theme.App\" parent=\"Theme.Material3.DynamicColors.DayNight\">\n"
                    + "        <item name=\"colorPrimary\">@color/primary</item>\n"
                    + "        <item name=\"colorOnPrimary\">@color/on_primary</item>\n"
                    + "    </style>\n"
                    + "</resources>\n");
        } else {
            Util.writeText(new File(res, "values/themes.xml"),
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n"
                    + "    <style name=\"Theme.App\" parent=\"android:Theme.Material.Light\">\n"
                    + "        <item name=\"android:colorPrimary\">@color/primary</item>\n"
                    + "    </style>\n"
                    + "</resources>\n");
            Util.writeText(new File(res, "values-night/themes.xml"),
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n"
                    + "    <style name=\"Theme.App\" parent=\"android:Theme.Material\">\n"
                    + "        <item name=\"android:colorPrimary\">@color/primary</item>\n"
                    + "    </style>\n"
                    + "</resources>\n");
        }

        // layout + MainActivity
        if (material) {
            Util.writeText(new File(res, "layout/activity_main.xml"),
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<androidx.coordinatorlayout.widget.CoordinatorLayout\n"
                    + "    xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                    + "    android:layout_width=\"match_parent\"\n"
                    + "    android:layout_height=\"match_parent\">\n\n"
                    + "    <TextView\n"
                    + "        android:layout_width=\"wrap_content\"\n"
                    + "        android:layout_height=\"wrap_content\"\n"
                    + "        android:layout_gravity=\"center\"\n"
                    + "        android:text=\"@string/hello_world\"\n"
                    + "        android:textAppearance=\"?attr/textAppearanceHeadlineSmall\"/>\n\n"
                    + "    <com.google.android.material.floatingactionbutton.FloatingActionButton\n"
                    + "        android:id=\"@+id/fab\"\n"
                    + "        android:layout_width=\"wrap_content\"\n"
                    + "        android:layout_height=\"wrap_content\"\n"
                    + "        android:layout_gravity=\"bottom|end\"\n"
                    + "        android:layout_margin=\"16dp\"\n"
                    + "        android:contentDescription=\"@string/fab_description\"\n"
                    + "        android:src=\"@android:drawable/ic_input_add\"/>\n"
                    + "</androidx.coordinatorlayout.widget.CoordinatorLayout>\n");

            Util.writeText(new File(pkgDir, "MainActivity.java"),
                    "package " + pkg + ";\n\n"
                    + "import android.os.Bundle;\n"
                    + "import android.view.View;\n"
                    + "import androidx.appcompat.app.AppCompatActivity;\n"
                    + "import com.google.android.material.floatingactionbutton.FloatingActionButton;\n"
                    + "import com.google.android.material.snackbar.Snackbar;\n\n"
                    + "public class MainActivity extends AppCompatActivity {\n"
                    + "    @Override\n"
                    + "    protected void onCreate(Bundle savedInstanceState) {\n"
                    + "        super.onCreate(savedInstanceState);\n"
                    + "        setContentView(R.layout.activity_main);\n"
                    + "        FloatingActionButton fab = findViewById(R.id.fab);\n"
                    + "        fab.setOnClickListener(new View.OnClickListener() {\n"
                    + "            @Override\n"
                    + "            public void onClick(View view) {\n"
                    + "                Snackbar.make(view, R.string.fab_clicked, Snackbar.LENGTH_SHORT).show();\n"
                    + "            }\n"
                    + "        });\n"
                    + "    }\n"
                    + "}\n");
        } else {
            Util.writeText(new File(res, "layout/activity_main.xml"),
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                    + "    android:layout_width=\"match_parent\"\n"
                    + "    android:layout_height=\"match_parent\"\n"
                    + "    android:orientation=\"vertical\"\n"
                    + "    android:gravity=\"center\">\n"
                    + "    <TextView\n"
                    + "        android:layout_width=\"wrap_content\"\n"
                    + "        android:layout_height=\"wrap_content\"\n"
                    + "        android:text=\"@string/hello_world\"\n"
                    + "        android:textSize=\"24sp\"/>\n"
                    + "</LinearLayout>\n");

            Util.writeText(new File(pkgDir, "MainActivity.java"),
                    "package " + pkg + ";\n\n"
                    + "import android.app.Activity;\n"
                    + "import android.os.Bundle;\n\n"
                    + "public class MainActivity extends Activity {\n"
                    + "    @Override\n"
                    + "    protected void onCreate(Bundle savedInstanceState) {\n"
                    + "        super.onCreate(savedInstanceState);\n"
                    + "        setContentView(R.layout.activity_main);\n"
                    + "    }\n"
                    + "}\n");
        }

        // Compose: satu file Kotlin dengan @Composable. Compile Kotlin mendeteksi runtime
        // Compose di classpath -> plugin compiler Compose aktif (rewrite Composer/$changed).
        // Tema: Material3 via setContent; tak butuh layout XML.
        if (compose) {
            Util.writeText(new File(pkgDir, "MainActivity.kt"),
                    "package " + pkg + "\n\n"
                    + "import android.os.Bundle\n"
                    + "import androidx.activity.ComponentActivity\n"
                    + "import androidx.activity.compose.setContent\n"
                    + "import androidx.compose.foundation.layout.fillMaxSize\n"
                    + "import androidx.compose.foundation.layout.padding\n"
                    + "import androidx.compose.material3.MaterialTheme\n"
                    + "import androidx.compose.material3.Scaffold\n"
                    + "import androidx.compose.material3.Text\n"
                    + "import androidx.compose.runtime.Composable\n"
                    + "import androidx.compose.ui.Modifier\n"
                    + "import androidx.compose.ui.unit.dp\n\n"
                    + "class MainActivity : ComponentActivity() {\n"
                    + "    override fun onCreate(savedInstanceState: Bundle?) {\n"
                    + "        super.onCreate(savedInstanceState)\n"
                    + "        setContent { App() }\n"
                    + "    }\n"
                    + "}\n\n"
                    + "@Composable\n"
                    + "fun App() {\n"
                    + "    MaterialTheme {\n"
                    + "        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->\n"
                    + "            Text(\n"
                    + "                text = \"Hello, Compose!\",\n"
                    + "                modifier = Modifier.padding(innerPadding).padding(16.dp),\n"
                    + "            )\n"
                    + "        }\n"
                    + "    }\n"
                    + "}\n");
        }

        // manifest
        Util.writeText(ProjectConfig.manifestFile(root),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"" + pkg + "\">\n"
                + "    <application\n"
                + "        android:allowBackup=\"true\"\n"
                + "        android:icon=\"@mipmap/ic_launcher\"\n"
                + "        android:label=\"@string/app_name\"\n"
                + "        android:roundIcon=\"@mipmap/ic_launcher_round\"\n"
                + "        android:supportsRtl=\"true\"\n"
                + "        android:theme=\"@style/Theme.App\">\n"
                + "        <activity android:name=\".MainActivity\" android:exported=\"true\">\n"
                + "            <intent-filter>\n"
                + "                <action android:name=\"android.intent.action.MAIN\"/>\n"
                + "                <category android:name=\"android.intent.category.LAUNCHER\"/>\n"
                + "            </intent-filter>\n"
                + "        </activity>\n"
                + "    </application>\n"
                + "</manifest>\n");

        return true;
    }
}
