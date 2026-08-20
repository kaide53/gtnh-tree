package gtnhtreeexport;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

import net.minecraft.client.Minecraft;

/**
 * 模组配置：目前只有"服务器地址"一项，存到 config/gtnhtreeexport.cfg（单行文本）。
 */
public class ModConfig {

    private static String serverUrl = "";
    private static boolean loaded = false;

    public static File configFile() {
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "config"), "gtnhtreeexport.cfg");
    }

    public static synchronized String getServerUrl() {
        if (!loaded) {
            load();
        }
        return serverUrl;
    }

    public static synchronized void setServerUrl(String url) {
        serverUrl = url == null ? "" : url.trim();
        loaded = true;
        save();
    }

    private static void load() {
        loaded = true;
        try {
            File f = configFile();
            if (!f.exists()) {
                return;
            }
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line = r.readLine();
            r.close();
            if (line != null) {
                serverUrl = line.trim();
            }
        } catch (Exception e) {
            // 忽略：首次运行无配置文件
        }
    }

    private static void save() {
        try {
            File f = configFile();
            File parent = f.getParentFile();
            if (!parent.exists()) {
                parent.mkdirs();
            }
            FileWriter w = new FileWriter(f);
            w.write(serverUrl + "\n");
            w.close();
        } catch (Exception e) {
            // 忽略
        }
    }
}
