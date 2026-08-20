package gtnhtreeexport;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import cpw.mods.fml.common.registry.GameData;

import codechicken.nei.ItemPanels;
import codechicken.nei.NEIClientConfig;
import codechicken.nei.bookmark.BookmarkGrid;
import codechicken.nei.bookmark.BookmarkItem;
import codechicken.nei.bookmark.BookmarkStorage;
import codechicken.nei.recipe.Recipe.RecipeId;

/**
 * 导出命令：
 *   /treeexport         导出所有书签组的配方链（含物品图标）
 *   /treeexport <组ID>  只导出指定组
 *
 * 数据来自 NEI 的书签（BookmarkStorage），树的重建逻辑与 web/tools/bookmarks2tree.py 一致。
 * 每个物品会用游戏自身的渲染管线画成 16x16 PNG，存到 dumps/icons/，JSON 里通过 icon 字段引用。
 */
public class TreeExportCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "treeexport";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/treeexport [组ID]  把 NEI 书签配方链导出为 dumps/tree.json（含图标到 dumps/icons/）";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> opts = new ArrayList<String>();
            if ("config".startsWith(prefix)) {
                opts.add("config");
            }
            if ("settings".startsWith(prefix)) {
                opts.add("settings");
            }
            return opts;
        }
        return null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length > 0 && ("config".equalsIgnoreCase(args[0]) || "settings".equalsIgnoreCase(args[0]))) {
            TreeConfigGui.request();
            return;
        }
        int onlyGroup = -1;
        if (args.length > 0) {
            try {
                onlyGroup = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                sender.addChatMessage(new ChatComponentText("组ID 必须是数字: " + args[0]));
                return;
            }
        }
        try {
            ExportResult result = export(onlyGroup);
            sender.addChatMessage(new ChatComponentText(
                    "[GTNH合成树] 已导出: " + result.file.getAbsolutePath()
                            + (result.iconCount > 0 ? "（正在生成 " + result.iconCount + " 个图标…）" : "")));
        } catch (Exception e) {
            NEIClientConfig.logger.error("[GTNH合成树] 导出失败: {}", e.getMessage());
            sender.addChatMessage(new ChatComponentText("[GTNH合成树] 导出失败: " + e.getMessage()));
        }
    }

    private static final class ExportResult {
        final File file;
        final int iconCount;

        ExportResult(File file, int iconCount) {
            this.file = file;
            this.iconCount = iconCount;
        }
    }

    private ExportResult export(int onlyGroup) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();

        // 先把 NEI 内存里的最新书签刷到磁盘，避免读到退出世界前才写盘的旧数据
        ItemPanels.bookmarkPanel.save();

        // 与 NEI BookmarkPanel.load() 相同的文件定位逻辑
        String worldPath = "global";
        if (NEIClientConfig.getBooleanSetting("inventory.bookmarks.worldSpecific")) {
            worldPath = NEIClientConfig.getWorldPath();
        }
        File bookmarkFile = new File(new File(new File(mc.mcDataDir, "saves/NEI"), worldPath), "bookmarks.ini");
        if (!bookmarkFile.exists()) {
            bookmarkFile = new File(NEIClientConfig.configDir, "bookmarks.ini");
        }
        if (!bookmarkFile.exists()) {
            throw new IllegalStateException("找不到书签文件: " + bookmarkFile.getAbsolutePath());
        }

        BookmarkStorage storage = new BookmarkStorage();
        storage.load(bookmarkFile);
        BookmarkGrid grid = storage.getActiveGrid();

        JsonArray roots = new JsonArray();
        Map<String, ItemStack> allStacks = new HashMap<>();
        int exportedGroups = 0;
        for (int gid = 0; gid < 64; gid++) {
            if (grid.getGroup(gid) == null) {
                continue;
            }
            if (onlyGroup >= 0 && gid != onlyGroup) {
                continue;
            }
            if (!grid.isCraftingMode(gid)) {
                continue;
            }
            Map<Integer, BookmarkItem> chainItems = grid.createChainItems(gid);
            if (chainItems.isEmpty()) {
                continue;
            }
            roots.addAll(buildGroup(chainItems, allStacks));
            exportedGroups++;
        }

        if (exportedGroups == 0) {
            throw new IllegalStateException("没有可导出的配方链（书签里没有 crafting 模式的配方组？）");
        }

        File dir = new File(mc.mcDataDir, "dumps");
        if (!dir.exists()) {
            dir.mkdirs();
        }

        JsonObject doc = new JsonObject();
        doc.addProperty("schemaVersion", 1);
        doc.addProperty("source", "nei-chain-export");
        doc.addProperty("exportedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date()));
        doc.add("roots", roots);

        File out = new File(dir, "tree.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        FileWriter w = new FileWriter(out);
        try {
            gson.toJson(doc, w);
        } finally {
            w.close();
        }

        // 打开图标导出屏（在正常 GUI 渲染循环里画图标并截图，保证和游戏内显示一致）
        File iconsDir = new File(dir, "icons");
        List<String> keys = new ArrayList<>(collectKeys(roots));
        Collections.sort(keys);
        List<ItemStack> iconStacks = new ArrayList<>();
        List<String> iconFiles = new ArrayList<>();
        for (String key : keys) {
            ItemStack st = allStacks.get(key);
            if (st == null) {
                continue;
            }
            ItemStack one = st.copy();
            one.stackSize = 1;
            iconStacks.add(one);
            iconFiles.add(iconFile(key));
        }
        if (!iconStacks.isEmpty()) {
            IconExportScreen.request(iconStacks, iconFiles, iconsDir);
        }

        return new ExportResult(out, iconStacks.size());
    }

    // ------------------------------------------------------------------
    // 树重建（与 bookmarks2tree.py 一致）
    // ------------------------------------------------------------------

    private static final class ResultInfo {
        final long factor;
        final long multiplier;
        final RecipeId recipeId;

        ResultInfo(long factor, long multiplier, RecipeId recipeId) {
            this.factor = factor;
            this.multiplier = multiplier;
            this.recipeId = recipeId;
        }
    }

    private static final class IngInfo {
        final String key;
        final long factor;

        IngInfo(String key, long factor) {
            this.key = key;
            this.factor = factor;
        }
    }

    private JsonArray buildGroup(Map<Integer, BookmarkItem> chainItems, Map<String, ItemStack> stacks) {
        Map<String, ResultInfo> results = new LinkedHashMap<>();
        Map<String, List<IngInfo>> ingredientsByRecipe = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        Set<String> consumed = new HashSet<>();
        List<String> plainKeys = new ArrayList<>();

        for (BookmarkItem it : chainItems.values()) {
            String key = itemKey(it.itemStack);
            names.put(key, it.itemStack.getDisplayName());
            stacks.put(key, it.itemStack);
            if (it.type == BookmarkItem.BookmarkItemType.RESULT && it.recipeId != null) {
                if (!results.containsKey(key)) {
                    results.put(key, new ResultInfo(it.factor, it.multiplier, it.recipeId));
                }
            } else if (it.type == BookmarkItem.BookmarkItemType.INGREDIENT && it.recipeId != null) {
                String rk = recipeKey(it.recipeId);
                List<IngInfo> list = ingredientsByRecipe.get(rk);
                if (list == null) {
                    list = new ArrayList<>();
                    ingredientsByRecipe.put(rk, list);
                }
                list.add(new IngInfo(key, it.factor));
                consumed.add(key);
            } else {
                if (!consumed.contains(key)) {
                    plainKeys.add(key);
                }
            }
        }

        List<String> rootKeys = new ArrayList<>();
        for (String k : results.keySet()) {
            if (!consumed.contains(k)) {
                rootKeys.add(k);
            }
        }
        if (rootKeys.isEmpty()) {
            rootKeys.addAll(results.keySet());
        }

        JsonArray roots = new JsonArray();
        for (String k : rootKeys) {
            ResultInfo r = results.get(k);
            long need = (r.multiplier > 0 ? r.multiplier : 1) * (r.factor > 0 ? r.factor : 1);
            roots.add(makeNode(k, r, need, results, ingredientsByRecipe, names, 0));
        }
        for (String k : plainKeys) {
            roots.add(makeLeaf(k, names.get(k)));
        }
        return roots;
    }

    private JsonObject makeNode(String key, ResultInfo r, long need, Map<String, ResultInfo> results,
            Map<String, List<IngInfo>> ingredientsByRecipe, Map<String, String> names, int depth) {
        JsonObject node = new JsonObject();
        node.addProperty("id", key);
        node.addProperty("name", names.containsKey(key) ? names.get(key) : "");
        node.addProperty("amount", need);
        node.addProperty("icon", "icons/" + iconFile(key));
        node.add("children", new JsonArray());

        if (r != null && r.recipeId != null && depth < 200) {
            long prod = r.factor > 0 ? r.factor : 1;
            long runs = need > 0 ? (need + prod - 1) / prod : 1;
            node.addProperty("produces", prod);
            node.addProperty("runs", runs);
            node.addProperty("leftover", runs * prod - need);

            JsonObject recipe = new JsonObject();
            recipe.addProperty("handler", r.recipeId.getHandleName());
            recipe.addProperty("machine", r.recipeId.getHandleName());
            node.add("recipe", recipe);

            String rk = recipeKey(r.recipeId);
            List<IngInfo> ings = ingredientsByRecipe.get(rk);
            if (ings != null && !ings.isEmpty()) {
                List<IngInfo> sorted = new ArrayList<>(ings);
                Collections.sort(sorted, new Comparator<IngInfo>() {
                    @Override
                    public int compare(IngInfo a, IngInfo b) {
                        return a.key.compareTo(b.key);
                    }
                });
                JsonArray children = (JsonArray) node.get("children");
                for (IngInfo ing : sorted) {
                    long childNeed = runs * ing.factor;
                    JsonObject child = makeNode(
                            ing.key, results.get(ing.key), childNeed, results, ingredientsByRecipe, names, depth + 1);
                    child.addProperty("perCraft", ing.factor);
                    children.add(child);
                }
            }
        }
        return node;
    }

    private JsonObject makeLeaf(String key, String name) {
        JsonObject node = new JsonObject();
        node.addProperty("id", key);
        node.addProperty("name", name == null ? "" : name);
        node.addProperty("amount", 1);
        node.addProperty("icon", "icons/" + iconFile(key));
        node.add("children", new JsonArray());
        return node;
    }

    private static String itemKey(ItemStack st) {
        String reg = GameData.getItemRegistry().getNameForObject(st.getItem());
        if (reg == null || reg.isEmpty()) {
            reg = "id:" + Item.getIdFromItem(st.getItem());
        }
        return reg + ":" + st.getItemDamage();
    }

    private static String recipeKey(RecipeId id) {
        StringBuilder sb = new StringBuilder();
        sb.append(id.getHandleName()).append('|');
        ItemStack result = id.getResult();
        sb.append(result != null ? itemKey(result) : "?").append('|');
        List<String> ingKeys = new ArrayList<>();
        for (ItemStack ing : id.getIngredients()) {
            ingKeys.add(itemKey(ing));
        }
        Collections.sort(ingKeys);
        for (String k : ingKeys) {
            sb.append(k).append(',');
        }
        return sb.toString();
    }

    private static String iconFile(String key) {
        return key.replaceAll("[^A-Za-z0-9._-]", "_") + ".png";
    }

    private static Set<String> collectKeys(JsonArray roots) {
        Set<String> keys = new HashSet<>();
        collectKeysRec(roots, keys);
        return keys;
    }

    private static void collectKeysRec(JsonArray nodes, Set<String> keys) {
        for (JsonElement el : nodes) {
            JsonObject n = el.getAsJsonObject();
            if (n.has("id")) {
                keys.add(n.get("id").getAsString());
            }
            if (n.has("children")) {
                collectKeysRec(n.getAsJsonArray("children"), keys);
            }
        }
    }
}
