package com.github.xiaozhaoz1.dshpet;

import com.github.xiaozhaoz1.dshpet.config.ConfigPaths;
import com.github.xiaozhaoz1.dshpet.render.PetHud;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * neoforge 1.21.1 客户端入口 —— <b>平台差异仅集中在此</b>。
 *
 * <p>四个注册点（都不含业务逻辑，只做接线）：</p>
 * <ol>
 *   <li>{@code RenderGuiEvent.Post}（抽象父类禁监听 ⇒ 用具体子类）—— 无界面时的 HUD 阶段</li>
 *   <li>{@code ScreenEvent.Render.Post}（{@code LOWEST} 优先级）—— 界面之上常驻显示</li>
 *   <li>{@code ClientPlayerNetworkEvent.LoggingOut} —— 释放纹理</li>
 *   <li>{@code RegisterClientCommandsEvent} —— {@code /dshpet debug on|off|status} 游戏内调试开关</li>
 * </ol>
 *
 * <p>构造器注入 {@code (IEventBus, ModContainer)} 是 MDG 的 mod 类注入形式（LMA 同款，已跑通）。</p>
 */
@Mod(value = DshPet.MOD_ID, dist = Dist.CLIENT)
public final class DshPetNeoForgeClient {

    public DshPetNeoForgeClient(IEventBus modBus, ModContainer modContainer) {
        ConfigPaths.init(FMLPaths.CONFIGDIR.get());

        // 配置注册（neoforge 1.21.1 入口：ModContainer.registerConfig；文件名 dshpet.toml）
        // 标准写法参考：LMA-MAIN LmaNeoForgeEntry L76（同款 ModConfig.Type.COMMON）
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON,
                com.github.xiaozhaoz1.dshpet.config.DshPetConfig.SPEC, DshPet.MOD_ID + ".toml");

        // 配置屏工厂（模组列表「配置」按钮）—— neoforge 1.21.1 用 IConfigScreenFactory
        // （javap 实证：createScreen(ModContainer, Screen)；LMA LmaNeoForgeEntry 同款写法已跑通）
        modContainer.registerExtensionPoint(net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                (container, parent) -> new com.github.xiaozhaoz1.dshpet.screen.DshPetConfigScreen(parent));

        // ① HUD 阶段（无界面时）
        NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class, ev ->
                PetHud.render(ev.getGuiGraphics(), ConfigPaths.animationsDir()));

        // ② 界面渲染之后（暂停菜单/设置等界面之上仍可见）
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,
                ScreenEvent.Render.Post.class, ev ->
                        PetHud.render(ev.getGuiGraphics(), ConfigPaths.animationsDir()));

        // ③ 世界卸载/断线: 释放纹理
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, ev ->
                PetHud.reset());

        // ④ 客户端命令: /dshpet debug on|off|status —— 游戏内切换调试日志（免改 JVM 参数）
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, ev ->
                DshPetCommand.register(ev.getDispatcher()));
    }
}
