package gtnhtreeexport;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 客户端 tick 处理器：
 *   1. 打开挂起的 GuiScreen（图标导出屏 / 设置屏）。命令是在聊天框里执行的，直接打开
 *      会被聊天框的"关闭屏幕"覆盖，所以推迟到下一 tick。
 *   2. 打印后台线程（上传器）排队的聊天消息。
 */
public class ClientTickHandler {

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        IconExportScreen iconScreen = IconExportScreen.consumePending();
        if (iconScreen != null) {
            Minecraft.getMinecraft().displayGuiScreen(iconScreen);
            return;
        }

        TreeConfigGui configGui = TreeConfigGui.consumePending();
        if (configGui != null) {
            Minecraft.getMinecraft().displayGuiScreen(configGui);
            return;
        }

        if (Minecraft.getMinecraft().thePlayer != null) {
            String msg;
            while ((msg = TreeUploader.consumeMessage()) != null) {
                Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(msg));
            }
        }
    }
}
