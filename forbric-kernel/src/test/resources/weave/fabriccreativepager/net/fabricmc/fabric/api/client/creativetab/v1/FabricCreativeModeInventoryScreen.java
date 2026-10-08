package net.fabricmc.fabric.api.client.creativetab.v1;
public interface FabricCreativeModeInventoryScreen {
    int getCurrentPage(); boolean switchToPage(int page);
    default boolean switchToPreviousPage(){return switchToPage(getCurrentPage()-1);}
    default boolean switchToNextPage(){return switchToPage(getCurrentPage()+1);}
}
