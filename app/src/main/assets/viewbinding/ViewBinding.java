package androidx.viewbinding;

/**
 * Interface minimal ViewBinding (cara CodeAssist/sintesis).
 * Satu-satunya runtime yang dibutuhkan oleh class Binding hasil generate.
 */
public interface ViewBinding {
    android.view.View getRoot();
}
