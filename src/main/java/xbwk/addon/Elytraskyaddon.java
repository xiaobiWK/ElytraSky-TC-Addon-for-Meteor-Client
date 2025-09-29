package xbwk.addon;

import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.api.ClientModInitializer;
import xbwk.addon.modules.*;
import xbwk.addon.modules.searcharea.SearchArea;

public class Elytraskyaddon extends MeteorAddon implements ClientModInitializer {
    public static final Category CATEGORY = new Category("ElytraSky");

    private static Elytraskyaddon instance;

    public Elytraskyaddon() {
        instance = this;
    }

    public static Elytraskyaddon getInstance() {
        return instance;
    }

    @Override
    public void onInitialize() {
        Modules.get().add(new Advertise());
        Modules.get().add(new AutoLoginXin());
        Modules.get().add(new ChickenNametags());
        Modules.get().add(new ElytraFlyXin());
        Modules.get().add(new ElytraReplace());
        Modules.get().add(new BaseFinderPlus());
        Modules.get().add(new Botmode());
        Modules.get().add(new GotoPosition());
        Modules.get().add(new SearchArea());
        Modules.get().add(new BaseFinderXin());
        Modules.get().add(new Yuzusayings());
        System.out.println("===ElytraSky Addon===");
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "xbwk.addon";
    }

    @Override
    public void onInitializeClient() {
        System.out.println("ElytraSky Addon initialized!");
    }

    public static String getAddonName() {
        return "ElytraSky Addon";
    }

    public static String getVersion() {
        return "0.0.2";
    }
}