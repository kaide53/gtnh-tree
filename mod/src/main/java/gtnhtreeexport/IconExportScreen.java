package gtnhtreeexport;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.List;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import codechicken.lib.gui.GuiDraw;
import codechicken.nei.guihook.GuiContainerManager;

/**
 * 物品图标导出屏。
 *
 * 直接沿用 GTNH NotEnoughItems 里 GuiItemIconDumper 的做法：在 GuiScreen 的正常渲染循环里
 * （此时游戏的 GL 状态正确）把物品画到主帧缓冲，再截图、按格子裁切成单个 PNG。这样画出来的
 * 图标和游戏里显示完全一致（含 GT 多层材质/染色、3D 方块物品）。
 */
public class IconExportScreen extends GuiScreen {

    private static final int ICON_SIZE = 16;
    private static final int BORDER_SIZE = 1;
    private static final int BOX_SIZE = ICON_SIZE + BORDER_SIZE * 2;

    private final List<ItemStack> stacks;
    private final List<String> files;
    private final File dir;
    private int drawIndex;
    private int parseIndex;

    private static IconExportScreen pending;

    /** 在聊天命令里调用：把导出请求挂起，交给下一 tick 打开（避免被聊天框关闭屏幕覆盖）。 */
    public static void request(List<ItemStack> stacks, List<String> files, File dir) {
        pending = new IconExportScreen(stacks, files, dir);
    }

    /** 由 tick 处理器调用：取出挂起的导出请求（若存在）。 */
    public static IconExportScreen consumePending() {
        IconExportScreen p = pending;
        pending = null;
        return p;
    }

    public IconExportScreen(List<ItemStack> stacks, List<String> files, File dir) {
        this.stacks = stacks;
        this.files = files;
        this.dir = dir;
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    @Override
    public void drawScreen(int mousex, int mousey, float frame) {
        try {
            drawItems();
            exportItems();
        } catch (Exception e) {
            e.printStackTrace();
            Minecraft.getMinecraft().displayGuiScreen(null);
        }
    }

    private void drawItems() {
        Dimension d = GuiDraw.displayRes();

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0.0D, d.width * 16D / ICON_SIZE, d.height * 16D / ICON_SIZE, 0.0D, 1000.0D, 3000.0D);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        int rows = d.height / BOX_SIZE;
        int cols = d.width / BOX_SIZE;
        int fit = rows * cols;

        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glColor4f(1, 1, 1, 1);

        for (int i = 0; drawIndex < stacks.size() && i < fit; drawIndex++, i++) {
            int x = i % cols * 18;
            int y = i / cols * 18;
            GuiContainerManager.drawItem(x + BORDER_SIZE, y + BORDER_SIZE, stacks.get(drawIndex));
        }

        GL11.glFlush();
    }

    private void exportItems() throws IOException {
        BufferedImage img = screenshot();
        int rows = img.getHeight() / BOX_SIZE;
        int cols = img.getWidth() / BOX_SIZE;
        int fit = rows * cols;

        for (int i = 0; parseIndex < stacks.size() && i < fit; parseIndex++, i++) {
            int x = i % cols * BOX_SIZE;
            int y = i / cols * BOX_SIZE;
            BufferedImage sub = img.getSubimage(x + BORDER_SIZE, y + BORDER_SIZE, ICON_SIZE, ICON_SIZE);
            ImageIO.write(sub, "png", new File(dir, files.get(parseIndex)));
        }

        if (parseIndex >= stacks.size()) {
            Minecraft.getMinecraft().displayGuiScreen(null);
            Minecraft.getMinecraft().thePlayer.addChatMessage(
                    new ChatComponentText("[GTNH合成树] 图标已导出到 dumps/icons/"));

            // 上传到服务器（若配置了地址）
            File treeJson = new File(dir.getParentFile(), "tree.json");
            String url = ModConfig.getServerUrl();
            String player = Minecraft.getMinecraft().thePlayer.getGameProfile().getName();
            TreeUploader.upload(treeJson, dir, url, player);
        }
    }

    private IntBuffer pixelBuffer;
    private int[] pixelValues;

    private BufferedImage screenshot() {
        Framebuffer fb = Minecraft.getMinecraft().getFramebuffer();
        Dimension mcSize = GuiDraw.displayRes();
        Dimension texSize = mcSize;

        if (OpenGlHelper.isFramebufferEnabled()) {
            texSize = new Dimension(fb.framebufferTextureWidth, fb.framebufferTextureHeight);
        }

        int k = texSize.width * texSize.height;
        if (pixelBuffer == null || pixelBuffer.capacity() < k) {
            pixelBuffer = BufferUtils.createIntBuffer(k);
            pixelValues = new int[k];
        }

        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        pixelBuffer.clear();

        if (OpenGlHelper.isFramebufferEnabled()) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, fb.framebufferTexture);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);
        } else {
            GL11.glReadPixels(
                    0,
                    0,
                    texSize.width,
                    texSize.height,
                    GL12.GL_BGRA,
                    GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                    pixelBuffer);
        }

        pixelBuffer.get(pixelValues);
        TextureUtil.func_147953_a(pixelValues, texSize.width, texSize.height);

        BufferedImage img = new BufferedImage(mcSize.width, mcSize.height, BufferedImage.TYPE_INT_ARGB);
        if (OpenGlHelper.isFramebufferEnabled()) {
            int yOff = texSize.height - mcSize.height;
            for (int y = 0; y < mcSize.height; ++y) {
                for (int x = 0; x < mcSize.width; ++x) {
                    img.setRGB(x, y, pixelValues[(y + yOff) * texSize.width + x]);
                }
            }
        } else {
            img.setRGB(0, 0, texSize.width, height, pixelValues, 0, texSize.width);
        }

        return img;
    }
}
