package gtnhtreeexport;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.client.ClientCommandHandler;

/**
 * GTNH 合成树导出模组。
 *
 * 纯客户端模组：进游戏后输入 /treeexport 即可把当前 NEI 书签里的配方链
 * 导出成 dumps/tree.json（与网页 viewer.html 配套）。
 *
 * 依赖：GTNH 版 NotEnoughItems（2.8.x-GTNH，整合包自带）。
 */
@Mod(
        modid = GTNHTreeExport.MODID,
        name = "GTNH Tree Export",
        version = GTNHTreeExport.VERSION,
        acceptedMinecraftVersions = "[1.7.10]")
public class GTNHTreeExport {

    public static final String MODID = "gtnhtreeexport";
    public static final String VERSION = "1.0.0";

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (FMLCommonHandler.instance().getSide().isClient()) {
            ClientCommandHandler.instance.registerCommand(new TreeExportCommand());
            FMLCommonHandler.instance().bus().register(new ClientTickHandler());
        }
    }
}
