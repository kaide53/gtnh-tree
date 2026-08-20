package gtnhtreeexport;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

/**
 * 设置界面：填写服务器地址。由 /treeexport config 打开。
 */
public class TreeConfigGui extends GuiScreen {

    private static TreeConfigGui pending;

    private GuiTextField urlField;
    private GuiButton scaleButton;

    public static void request() {
        pending = new TreeConfigGui();
    }

    public static TreeConfigGui consumePending() {
        TreeConfigGui g = pending;
        pending = null;
        return g;
    }

    @Override
    public void initGui() {
        this.urlField = new GuiTextField(this.fontRendererObj, this.width / 2 - 150, 70, 300, 20);
        this.urlField.setMaxStringLength(256);
        this.urlField.setText(ModConfig.getServerUrl());
        this.scaleButton = new GuiButton(2, this.width / 2 - 150, 120, 300, 20, scaleLabel());
        this.buttonList.add(this.scaleButton);
        this.buttonList.add(new GuiButton(0, this.width / 2 - 102, this.height / 2 + 40, 100, 20, "保存"));
        this.buttonList.add(new GuiButton(1, this.width / 2 + 2, this.height / 2 + 40, 100, 20, "取消"));
    }

    private static String scaleLabel() {
        int s = ModConfig.getIconScale();
        return "图标分辨率: " + s + "x (" + (16 * s) + "px)";
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            ModConfig.setServerUrl(this.urlField.getText());
            this.mc.displayGuiScreen(null);
        } else if (button.id == 1) {
            this.mc.displayGuiScreen(null);
        } else if (button.id == 2) {
            int next = ModConfig.getIconScale() + 1;
            if (next > 4) {
                next = 1;
            }
            ModConfig.setIconScale(next);
            this.scaleButton.displayString = scaleLabel();
        }
    }

    @Override
    protected void keyTyped(char c, int k) {
        if (this.urlField.textboxKeyTyped(c, k)) {
            return;
        }
        super.keyTyped(c, k);
    }

    @Override
    protected void mouseClicked(int x, int y, int b) {
        super.mouseClicked(x, y, b);
        this.urlField.mouseClicked(x, y, b);
    }

    @Override
    public void drawScreen(int x, int y, float f) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRendererObj, "GTNH 合成树 · 设置", this.width / 2, 30, 0xFFFFFF);
        this.drawString(this.fontRendererObj, "服务器地址（留空则不上传）:", this.width / 2 - 150, 56, 0xA0A0A0);
        this.urlField.drawTextBox();
        this.drawString(this.fontRendererObj, "例：http://192.168.1.10:8080", this.width / 2 - 150, 96, 0x666666);
        this.drawString(this.fontRendererObj, "图标导出分辨率（点击切换，越高越清晰但图标更大）:", this.width / 2 - 150, 108, 0xA0A0A0);
        super.drawScreen(x, y, f);
    }
}
