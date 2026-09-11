package io.github.psd2live.ui;

import java.nio.file.Path;
import javax.swing.SwingUtilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.nfd.NFDFilterItem;
import org.lwjgl.util.nfd.NFDOpenDialogArgs;
import org.lwjgl.util.nfd.NativeFileDialog;

/** Headless acceptance under the shipped JVM and shipped JARs, not the development JDK. */
public final class PackagedNativeProbe {
    public static void main(String[] args) throws Exception {
        Path runtime = Path.of(System.getProperty("java.home")).toRealPath();
        if (!runtime.equals(Path.of(args[0]).toRealPath())) {
            throw new AssertionError("Probe is using the wrong Java runtime: " + runtime);
        }
        System.out.println("Packaged Java runtime: " + runtime);
        var memory = MemoryUtil.memAlloc(8);
        try {
            memory.putLong(0, 123456789L);
            if (memory.getLong(0) != 123456789L) throw new AssertionError("Native memory roundtrip failed");
        } finally { MemoryUtil.memFree(memory); }
        SwingUtilities.invokeAndWait(() -> {
            if (NativeFileDialog.NFD_Init() != NativeFileDialog.NFD_OKAY) {
                throw new AssertionError(NativeFileDialog.NFD_GetError());
            }
            try (var stack = MemoryStack.stackPush()) {
                var filters = NFDFilterItem.calloc(1, stack);
                filters.get(0).name(stack.UTF8("PSD2Live 工程")).spec(stack.UTF8("psd2live"));
                var options = NFDOpenDialogArgs.calloc(stack).filterList(filters)
                    .defaultPath(stack.UTF8("C:\\人物 素材"));
                if (options.filterCount() != 1 || !"C:\\人物 素材".equals(options.defaultPathString())) {
                    throw new AssertionError("Native dialog arguments did not roundtrip");
                }
            } finally { NativeFileDialog.NFD_Quit(); }
        });
        System.out.println("PACKAGED_NATIVE_OK: memory, native library, COM and UTF-8 arguments; no windows opened");
    }
}
