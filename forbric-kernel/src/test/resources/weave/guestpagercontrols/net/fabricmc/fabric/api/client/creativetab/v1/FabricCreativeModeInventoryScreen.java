package net.fabricmc.fabric.api.client.creativetab.v1;
/** The public API by its own name: what the adapter recognises a guest pager by. */
public interface FabricCreativeModeInventoryScreen {
    int getCurrentPage(); boolean switchToPage(int page);
    default boolean switchToPreviousPage(){return switchToPage(getCurrentPage()-1);}
    default boolean switchToNextPage(){return switchToPage(getCurrentPage()+1);}
    default boolean hasAdditionalPages(){return true;}
}
