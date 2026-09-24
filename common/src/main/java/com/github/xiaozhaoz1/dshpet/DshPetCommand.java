package com.github.xiaozhaoz1.dshpet;

import com.github.xiaozhaoz1.dshpet.config.ConfigPaths;
import com.github.xiaozhaoz1.dshpet.config.SharedConfig;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetCatalog;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetDownloader;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetPack;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetStore;
import com.github.xiaozhaoz1.dshpet.download.HttpAssetFetch;
import com.github.xiaozhaoz1.dshpet.render.PetHud;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * 客户端命令族 —— 游戏内管理调试开关与素材（**主路径**：免改启动参数、免手工翻目录）。
 *
 * <pre>
 * /dshpet debug on|off|status    调试日志开关（写 config/dshpet.toml）
 * /dshpet assets                 用法提示
 * /dshpet assets default         下载并启用默认素材包（**素材不内嵌 jar 时的安装入口**）
 * /dshpet assets list            打印：已安装 / 当前启用
 * /dshpet assets install &lt;id&gt;   下载并启用指定包
 * /dshpet assets remove &lt;id&gt;    删除指定包
 * /dshpet model [id]             查看 / 切换启用素材包（换模型）
 * /dshpet about                  署名与许可（作者 · 地址 · 禁止商用）
 * </pre>
 *
 * <p><b>线程纪律</b>：清单获取/下载/解包全部在 {@link CompletableFuture} 异步链上；
 * 结果用 {@link Minecraft#execute} 回主线程（渲染与命令线程绝不阻塞、绝不联网）。</p>
 *
 * <p><b>反馈</b>：一律 {@link Component#translatable} + {@code zh_cn/en_us}（键对齐有测试守护）。</p>
 */
public final class DshPetCommand {

    private DshPetCommand() {
    }

    /** 供两平台入口在 {@code RegisterClientCommandsEvent} 里调用。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("dshpet")
                .then(Commands.literal("debug")
                        .then(Commands.literal("on").executes(ctx -> setDebug(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setDebug(ctx, false)))
                        .then(Commands.literal("status").executes(DshPetCommand::debugStatus)))
                .then(Commands.literal("assets")
                        .executes(DshPetCommand::assetsHelp)
                        .then(Commands.literal("default").executes(DshPetCommand::installDefault))
                        .then(Commands.literal("list").executes(DshPetCommand::listAssets))
                        .then(Commands.literal("install")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> downloadAndEnable(ctx,
                                                StringArgumentType.getString(ctx, "id")))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> removeById(ctx,
                                                StringArgumentType.getString(ctx, "id"))))))
                .then(Commands.literal("model")
                        .executes(DshPetCommand::modelList)
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> modelSwitch(ctx,
                                        StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("about").executes(DshPetCommand::about));
        dispatcher.register(root);
    }

    // ── debug ─────────────────────────────────────────────────────────────

    private static int setDebug(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                                boolean value) {
        SharedConfig.setDebug(value);
        DshPetLog.info(DshPetLog.CMD, "调试日志已{}（写入 config/dshpet.toml）", value ? "开启" : "关闭");
        msg(ctx, value ? "command.dshpet.debug.on" : "command.dshpet.debug.off");
        return 1;
    }

    private static int debugStatus(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        msg(ctx, "command.dshpet.debug.status", SharedConfig.debug() ? "on" : "off");
        return 1;
    }

    // ── assets ────────────────────────────────────────────────────────────

    /** 无参 `/dshpet assets` ⇒ 打开素材界面（下载/删除/启用都能点）；提示走界面页脚。 */
    private static int assetsHelp(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        msg(ctx, "dshpet.assets.help");
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(
                new com.github.xiaozhaoz1.dshpet.screen.AssetDownloadScreen(null)));
        return 1;
    }

    private static int installDefault(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        return downloadAndEnable(ctx, AssetPack.DEFAULT_PACK_ID);
    }

    /**
     * 异步：取清单 → 找包 → 下载 → 启用。结果回主线程。
     *
     * <p>失败一律给出可展示原因（网络/校验/未找到包），不静默。</p>
     */
    private static int downloadAndEnable(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                                        String packId) {
        msg(ctx, "dshpet.assets.installing", packId);
        Path cache = ConfigPaths.manifestCacheFile();
        Path packDir = AssetStore.packDir(packId);
        DshPetLog.info(DshPetLog.CMD, "开始下载素材包 '{}'（异步）", packId);

        CompletableFuture
                .supplyAsync(() -> AssetCatalog.load(new HttpAssetFetch(), cache, DshPet.VERSION))
                .thenApply(manifest -> {
                    AssetPack pack = manifest.byId(packId);
                    if (pack == null) {
                        if (manifest.packs().isEmpty()) {
                            return Message.of("dshpet.assets.no_manifest");
                        }
                        return AssetPack.DEFAULT_PACK_ID.equals(packId)
                                ? Message.of("dshpet.assets.no_default")
                                : Message.detail("dshpet.assets.unknown_pack", packId);
                    }
                    // 进度：每完成一个文件，回主线程在聊天栏更新一条提示（用户要求：只更新提示，不刷新多行）
                    AssetDownloader.Progress progress = new AssetDownloader.Progress() {
                        private int lastShown;

                        @Override
                        public void onFileDone(int done, int total, String file) {
                            // 节流：每 5% 或最后一个文件才播报（106 个文件 ⇒ 约 21 条，不刷屏）
                            int pct = total == 0 ? 100 : done * 100 / total;
                            if (pct - lastShown < 5 && done < total) {
                                return;
                            }
                            lastShown = pct;
                            Minecraft.getInstance().execute(() -> msg(ctx, "dshpet.assets.progress",
                                    String.valueOf(done), String.valueOf(total), String.valueOf(pct)));
                        }
                    };
                    AssetDownloader.Result r = AssetDownloader.download(pack, packDir, new HttpAssetFetch(),
                            progress);
                    return r.ok()
                            ? Message.ok(packId, r.assetCount())
                            : Message.detail("dshpet.assets.failed", packId, r.message());
                })
                .exceptionally(t -> Message.detail("dshpet.assets.failed", packId,
                        HttpAssetFetch.describe(t)))
                .thenAccept(result -> Minecraft.getInstance().execute(() -> {
                    if (result.ok()) {
                        SharedConfig.setActivePack(packId);
                        msg(ctx, "dshpet.assets.installed", packId, String.valueOf(result.assetCount()));
                        PetHud.reset(); // 新素材下次渲染即可加载
                    } else {
                        msg(ctx, result.key(), result.args());
                    }
                }));
        return 1;
    }

    private static int listAssets(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        var packs = AssetStore.installed();
        msg(ctx, "dshpet.assets.list.header", String.valueOf(packs.size()), activeOrNone());
        for (AssetStore.InstalledPack p : packs) {
            msg(ctx, "dshpet.assets.list.entry", p.id(), p.pack().version(),
                    p.pack().authorsText(), String.valueOf(p.animationCount()));
        }
        return 1;
    }

    private static int removeById(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                                 String id) {
        if (AssetStore.remove(id)) {
            PetHud.reset();
            msg(ctx, "dshpet.assets.removed", id);
        } else {
            msg(ctx, "dshpet.assets.not_installed", id);
        }
        return 1;
    }

    // ── model ─────────────────────────────────────────────────────────────

    private static int modelList(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        var packs = AssetStore.installed();
        if (packs.isEmpty()) {
            msg(ctx, "dshpet.assets.none_installed");
            return 1;
        }
        msg(ctx, "dshpet.assets.list.header", String.valueOf(packs.size()), activeOrNone());
        for (AssetStore.InstalledPack p : packs) {
            msg(ctx, "dshpet.assets.list.entry", p.id(), p.pack().version(),
                    p.pack().authorsText(), String.valueOf(p.animationCount()));
        }
        return 1;
    }

    private static int modelSwitch(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                                  String id) {
        if (!AssetStore.isInstalled(id)) {
            msg(ctx, "dshpet.assets.not_installed", id);
            return 0;
        }
        SharedConfig.setActivePack(id);
        PetHud.reset();
        DshPetLog.info(DshPetLog.CMD, "已切换素材包: {}", id);
        msg(ctx, "dshpet.assets.enabled", id);
        return 1;
    }

    // ── about ─────────────────────────────────────────────────────────────

    private static int about(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        // 署名数据源：已安装包的 pack.json（单一来源）；无包时给上游默认口径
        String who = "PC2005-cloud/dsh-pet";
        String website = "https://github.com/PC2005-cloud/dsh-pet";
        String license = "open-source use, NON-COMMERCIAL";
        var installed = AssetStore.installed();
        if (!installed.isEmpty()) {
            AssetPack p = installed.get(0).pack();
            who = p.authorsText();
            license = p.license();
            if (!p.website().isBlank()) {
                website = p.website();
            }
        }
        msg(ctx, "dshpet.about.line1", who + " · " + website);
        msg(ctx, "dshpet.about.line2", license);
        msg(ctx, "dshpet.about.line3");
        return 1;
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private static String activeOrNone() {
        String active = SharedConfig.activePack();
        return active == null || active.isBlank() ? "(none)" : active;
    }

    private static void msg(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                            String key, Object... args) {
        ctx.getSource().sendSuccess(() -> Component.translatable(key, args), false);
    }

    /** 异步结果：成功带文件数；失败带"可展示原因"的 lang 键与参数。 */
    private record Message(boolean ok, String key, Object[] args, int assetCount) {
        static Message ok(String id, int count) {
            return new Message(true, "dshpet.assets.installed",
                    new Object[] {id, String.valueOf(count)}, count);
        }

        static Message of(String key, Object... args) {
            return new Message(false, key, args, 0);
        }

        static Message detail(String key, Object... args) {
            return new Message(false, key, args, 0);
        }
    }
}
