package forbric.compatui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.server.IntegratedServer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** An actual displayed late prompt, clicked through Screen.mouseClicked, then a distinct declined finding. */
@Mod("forbriccompatui")
public final class CompatibilityUiControl {
    private static int phase, ticks, visibleTicks;
    private static boolean done, clicked, closed, focusSafe;
    private static IntegratedServer server;
    private static final long began = System.nanoTime();
    private static final String token = System.getProperty("compatui.token", "");
    public CompatibilityUiControl() {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick(Minecraft.getInstance()));
    }
    private static void tick(Minecraft client) {
        if (done) return;
        try {
            if (System.nanoTime() - began > 180_000_000_000L) throw new IllegalStateException("UI scenario timed out at phase " + phase);
            if (phase == 0 && client.level != null && client.player != null && ++ticks >= 40) {
                Path root = client.gameDirectory.toPath();
                require(!token.isEmpty() && Files.readString(root.resolve(".compat-ui-owned")).strip().equals(token), "unowned test instance");
                require(CompatibilityFindings.confirmedRequired().isEmpty(), "fixture already has a required failure");
                server = client.getSingleplayerServer(); require(server != null, "not an integrated world");
                record("continue"); phase = 1; ticks = 0;
            } else if ((phase == 1 || phase == 3) && client.gui.screen() instanceof ConfirmScreen screen
                    && screen.getClass().getName().equals("net.forbric.kernel.runtime.KernelCompatibilityScreen")) {
                if (++visibleTicks < 20) return;
                var no = ConfirmScreen.class.getDeclaredField("noButton"); no.setAccessible(true);
                focusSafe = screen.getFocused() == no.get(screen); require(focusSafe, "refusal is not initially focused");
                Screenshot.grab(client, false);
                if (phase == 1) {
                    var yes = ConfirmScreen.class.getDeclaredField("yesButton"); yes.setAccessible(true);Button button = (Button) yes.get(screen);
                    clicked = screen.mouseClicked(new MouseButtonEvent(button.getX()+button.getWidth()/2d, button.getY()+button.getHeight()/2d, new MouseButtonInfo(0,0)), false);
                    require(clicked, "native screen rejected the Continue click"); phase = 2; ticks = 0;
                } else { screen.onClose(); closed = true; phase = 4; ticks = 0; }
                visibleTicks = 0;
            } else if (phase == 2 && ++ticks >= 25) {
                require(client.level != null && client.player != null, "explicit Continue did not preserve the world");
                require(!(client.gui.screen() instanceof ConfirmScreen), "accepted prompt was not dismissed");
                require(CompatibilityFindings.confirmedRequired().size() == 1, "Continue erased the finding");
                record("close"); phase = 3;
            } else if (phase == 4 && client.level == null && client.player == null && server.isStopped() && ++ticks >= 25) {
                require(client.gui.screen() instanceof TitleScreen, "closing did not return to title");
                require(CompatibilityFindings.confirmedRequired().size() == 2, "closing erased failure evidence");
                finish(client, true, "");
            }
        } catch (Throwable failure) { failure.printStackTrace(); finish(client, false, failure.toString()); }
    }
    private static void record(String scenario) throws InterruptedException {
        Thread producer = new Thread(() -> CompatibilityFindings.record(new CompatibilityFinding("ui-control:"+scenario,
                "forbriccompatui", "Test " + scenario, "actual-client-canary", CompatibilityFinding.Confidence.CONFIRMED,
                true, "Deliberately injected test finding", List.of("owned instance token="+token))), "compat-ui-test-producer");
        producer.start();producer.join();
    }
    private static void finish(Minecraft client, boolean pass, String detail) {
        done = true;
        try { Files.writeString(client.gameDirectory.toPath().resolve("ui-proof.json"), "{\"token\":\""+token+"\",\"pass\":"+pass
                +",\"continueClicked\":"+clicked+",\"closeDeclined\":"+closed+",\"initialFocusRefuses\":"+focusSafe
                +",\"serverStopped\":"+(server!=null&&server.isStopped())+",\"requiredFindings\":"+CompatibilityFindings.confirmedRequired().size()
                +",\"phase\":"+phase+",\"detail\":\""+detail.replace("\\","\\\\").replace("\"","\\\"").replace("\n"," ")+"\"}\n"); }
        catch (Exception failure) { failure.printStackTrace(); }
        System.out.println("[CompatibilityUi] "+(pass?"PASS":"FAIL")+" phase="+phase+" "+detail);
        if (client.level != null) client.disconnectWithSavingScreen(); client.stop();
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
}
