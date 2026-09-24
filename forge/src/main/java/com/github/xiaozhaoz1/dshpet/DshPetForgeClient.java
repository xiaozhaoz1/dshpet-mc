package com.github.xiaozhaoz1.dshpet;

import com.github.xiaozhaoz1.dshpet.config.ConfigPaths;
import com.github.xiaozhaoz1.dshpet.render.PetHud;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * forge 1.20.1 客户端入口 —— <b>平台差异仅集中在此</b>。
 *
 * <p><b>必须同时有两个注解</b>（2026-09-23 实机踩坑，错题 #D12）：</p>
 * <ul>
 *   <li>{@code @Mod(MOD_ID)} —— <b>mod 本体入口</b>。缺它 ⇒ 启动即 FATAL：
 *       {@code constructed 0 mods: [], but had 1 mods specified: [dshpet]} /
 *       {@code The following classes are missing}（forge 1.20.1 的 {@code @Mod} **没有 {@code dist} 成员**，
 *       与 neoforge 1.21.1 的 {@code @Mod(value=…, dist=…)} 不同 —— 这也是两版必须分开写的原因）</li>
 *   <li>{@code @Mod.EventBusSubscriber(..., bus = MOD)} —— 注册 mod 事件总线订阅者</li>
 * </ul>
 *
 * <p>渲染钩子两个（实测两版都存在 {@code ScreenEvent$Render$Post}）：</p>
 * <ol>
 *   <li>{@code RenderGuiOverlayEvent.Pre} —— 无界面时的 HUD 阶段</li>
 *   <li>{@code ScreenEvent.Render.Post}（{@code LOWEST} 优先级）—— 界面内容渲染之后，让宠物在暂停菜单之上可见</li>
 * </ol>
 * <p>两个钩子都调用同一个 {@link PetHud#render}，内部自带场景判断 ⇒ 任一时机都只画一次。</p>
 */
@Mod(DshPet.MOD_ID)
@Mod.EventBusSubscriber(modid = DshPet.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class DshPetForgeClient {

    /**
     * 构造器即入口（forge 1.20.1：{@code @Mod} 类的无参构造器在 mod 加载期被调用）。
     *
     * <p>用实例构造器而非静态块：静态块只在**类首次被加载**时执行，而 {@code @Mod} 类的构造
     * 由 FML 保证在加载期调用一次 ⇒ 用构造器可确保"配置注册/事件挂载"一定发生。</p>
     */
    public DshPetForgeClient() {
        ConfigPaths.init(FMLPaths.CONFIGDIR.get());

        // 配置注册（forge 1.20.1 入口：ModLoadingContext.registerConfig；文件名 dshpet.toml）
        // 标准写法参考：LMA-MAIN LittleMaidMoreAction L123（同款 ModConfig.Type.COMMON）
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.COMMON,
                com.github.xiaozhaoz1.dshpet.config.DshPetConfig.SPEC,
                DshPet.MOD_ID + ".toml");

        // 配置屏工厂（模组列表「配置」按钮）—— forge 1.20.1 用 ConfigScreenHandler.ConfigScreenFactory
        // （javap 实证：record 含 BiFunction<Minecraft, Screen, Screen> 构造器；LMA 同款写法已跑通）
        net.minecraftforge.fml.ModLoadingContext.get().registerExtensionPoint(
                net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory(
                        (mc, parent) -> new com.github.xiaozhaoz1.dshpet.screen.DshPetConfigScreen(parent)));


        // ① HUD 阶段（无界面时）
        MinecraftForge.EVENT_BUS.addListener((RenderGuiOverlayEvent.Pre ev) ->
                PetHud.render(ev.getGuiGraphics(), ConfigPaths.animationsDir()));

        // ② 界面渲染之后（暂停菜单/设置等界面之上仍可见）
        // 用 LOWEST 优先级 ⇒ 在所有 GUI 绘制之后执行, 确保宠物位于最上层
        MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.LOWEST,
                false, (ScreenEvent.Render.Post ev) ->
                        PetHud.render(ev.getGuiGraphics(), ConfigPaths.animationsDir()));

        // ③ 世界卸载/断线: 释放纹理
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut ev) ->
                PetHud.reset());

        // ④ 客户端命令 /dshpet debug on|off|status —— 游戏内切换调试日志（免改 JVM 参数）
        MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.client.event.RegisterClientCommandsEvent ev) ->
                        DshPetCommand.register(ev.getDispatcher()));
    }
}
