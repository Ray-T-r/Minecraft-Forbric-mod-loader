/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.util.Map;
import java.util.WeakHashMap;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.ui.CompatibilityDecision;
import net.forbric.kernel.util.ForbricLog;

/** The server's completed-tick boundary consumes late findings; producers only record evidence. */
public final class LateServerCompatibility {
 private record Seen(long revision, CompatibilityDecision.Policy policy, boolean stopped) { }
 private static final Map<Object,Seen> SERVERS = new WeakHashMap<>();
 private LateServerCompatibility() { }
 public static void tick(Object server, boolean dedicated, Runnable normalHalt) {
  if(!dedicated || server==null)return; // Integrated servers defer to the client's native screen.
  long revision=CompatibilityFindings.revision();var policy=CompatibilityDecision.policy();
  synchronized(SERVERS) {
   Seen seen=SERVERS.get(server);
   if(seen!=null&&(seen.stopped()||(seen.revision()==revision&&seen.policy()==policy)))return;
   SERVERS.put(server,new Seen(revision,policy,false));
  }
  boolean proceed=CompatibilityDecision.check(false);
  if(!CompatibilityFindings.confirmedRequired().isEmpty())KernelLoadReport.write();
  synchronized(SERVERS){SERVERS.put(server,new Seen(CompatibilityFindings.revision(),policy,!proceed));}
  if(!proceed) {
   ForbricLog.error("[Forbric/Compatibility] a required feature failed after startup; saving and stopping the dedicated server at the completed-tick boundary");
   normalHalt.run();
  }
 }
 static void reset(){synchronized(SERVERS){SERVERS.clear();}}
}
