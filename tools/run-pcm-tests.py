"""Compile PCM sources and run pure/transport/hook JVM checks without Gradle, lint or downloads.

Android/libxposed fixtures model APIs and identity delivery, not a real Android runtime.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

STUBS = {
    "android/os/SystemClock.java": """package android.os;
public class SystemClock { public static long now=1000;
 public static long elapsedRealtime(){return now;}
 public static long elapsedRealtimeNanos(){return now*1000000;} }
""",
    "android/os/Process.java": """package android.os;
public class Process { public static int uid=20000;
 public static final int THREAD_PRIORITY_BACKGROUND=10;
 public static int myUid(){return uid;} }
""",
    "android/os/Bundle.java": "package android.os; public class Bundle {}",
    "android/os/Looper.java": """package android.os;
public class Looper {private static final Looper MAIN=new Looper();
 public static Looper getMainLooper(){return MAIN;} public void quitSafely(){}}""",
    "android/os/HandlerThread.java": """package android.os;
public class HandlerThread {public HandlerThread(String n,int p){} public void start(){}
 public Looper getLooper(){return new Looper();}}""",
    "android/os/Handler.java": """package android.os;
import java.util.*;
public class Handler {
 static final Map<Runnable,Long> tasks=new LinkedHashMap<>(); final Looper looper;
 static final Map<Runnable,Looper> owners=new LinkedHashMap<>(); public static Looper executingLooper;
 public Handler(Looper l){looper=l;} public Looper getLooper(){return looper;}
 public boolean post(Runnable r){return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long delay){
  owners.put(r,looper);tasks.put(r,SystemClock.now+delay);return true;}
 public void removeCallbacks(Runnable r){tasks.remove(r);owners.remove(r);}
 public static void runTasks(){for(Runnable r:new ArrayList<>(tasks.keySet()))
  if(tasks.get(r)!=null && tasks.get(r)<=SystemClock.now){tasks.remove(r);
   executingLooper=owners.remove(r);try{r.run();}finally{executingLooper=null;}}}
 public static int pending(){return tasks.size();}
}""",
    "android/app/BroadcastOptions.java": """package android.app;
import android.os.Bundle;
public class BroadcastOptions {public static BroadcastOptions makeBasic(){return new BroadcastOptions();}
 public BroadcastOptions setShareIdentityEnabled(boolean b){return this;}
 public Bundle toBundle(){return new Bundle();}}""",
    "android/app/Application.java": """package android.app;
import android.content.Context;
public class Application {protected void attach(Context c){}}""",
    "android/content/Intent.java": """package android.content;
import java.util.*;
public class Intent {final String action;String pkg; final Map<String,Long> extras=new HashMap<>();
 public Intent(String a){action=a;} public String getAction(){return action;}
 public Intent setPackage(String p){pkg=p;return this;}public String getPackage(){return pkg;}
 public Intent putExtra(String k,long v){extras.put(k,v);return this;}
 public long getLongExtra(String k,long d){return extras.getOrDefault(k,d);}}""",
    "android/content/IntentFilter.java": """package android.content;
public class IntentFilter {public final String action;public IntentFilter(String a){action=a;}}""",
    "android/content/BroadcastReceiver.java": """package android.content;
public abstract class BroadcastReceiver {public int uid=-1;public String pkg;
 public int getSentFromUid(){return uid;}public String getSentFromPackage(){return pkg;}
 public abstract void onReceive(Context c,Intent i);}""",
    "android/content/pm/Signature.java": """package android.content.pm;
public class Signature {final int id;public Signature(int i){id=i;}
 public boolean equals(Object o){return o instanceof Signature && ((Signature)o).id==id;}
 public int hashCode(){return id;}}""",
    "android/content/pm/SigningInfo.java": """package android.content.pm;
public class SigningInfo {public Signature[] signers={new Signature(1)};
 public Signature[] getApkContentsSigners(){return signers;}}""",
    "android/content/pm/ApplicationInfo.java": "package android.content.pm; public class ApplicationInfo {public int uid;}",
    "android/content/pm/PackageInfo.java": """package android.content.pm;
public class PackageInfo {public ApplicationInfo applicationInfo=new ApplicationInfo();
 public SigningInfo signingInfo=new SigningInfo();}""",
    "android/content/pm/PackageManager.java": """package android.content.pm;
import java.util.*;
public class PackageManager {public static final int GET_SIGNING_CERTIFICATES=0x8000000,
 SIGNATURE_MATCH=0,PERMISSION_GRANTED=0;public boolean platform=true;
 public final Map<String,PackageInfo> packages=new HashMap<>();
 public Runnable beforeLookup;
 public PackageManager(){add("com.android.systemui",1000);add("com.tencent.qqmusic",20000);}
 public void add(String p,int uid){PackageInfo i=new PackageInfo();i.applicationInfo.uid=uid;packages.put(p,i);}
 public PackageInfo getPackageInfo(String p,int flags){Runnable action=beforeLookup;beforeLookup=null;
  if(action!=null)action.run();if(!packages.containsKey(p))throw new SecurityException();return packages.get(p);}
 public int getPackageUid(String p,int flags){return getPackageInfo(p,flags).applicationInfo.uid;}
 public int checkSignatures(String a,String b){return platform?SIGNATURE_MATCH:-1;}}""",
    "android/content/Context.java": """package android.content;
import android.content.pm.*;import android.os.*;import java.util.*;
public class Context {public static final int RECEIVER_EXPORTED=2;final String pkg;
 public final PackageManager pm=new PackageManager();public int permission=0;
 public final List<Intent> sent=new ArrayList<>();public final List<String> sentPermissions=new ArrayList<>();
 public final List<BroadcastReceiver> receivers=new ArrayList<>();public final List<String> actions=new ArrayList<>();
 public final List<String> receiverPermissions=new ArrayList<>();
 public Context(String p){pkg=p;}public String getPackageName(){return pkg;}
 public Context getApplicationContext(){return this;}public PackageManager getPackageManager(){return pm;}
 public int checkSelfPermission(String p){return permission;}
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,String p,Handler h,int flags){
  receivers.add(r);actions.add(f.action);receiverPermissions.add(p);return null;}
 public void sendBroadcast(Intent i,String p,Bundle b){sent.add(i);sentPermissions.add(p);}
 public void deliver(int uid,String sender,Intent i){for(int n=0;n<receivers.size();n++)
  if(actions.get(n).equals(i.getAction())){BroadcastReceiver r=receivers.get(n);r.uid=uid;r.pkg=sender;r.onReceive(this,i);}}
}""",
    "android/media/AudioTrack.java": """package android.media;
import java.nio.ByteBuffer;
public class AudioTrack {public static final int PLAYSTATE_PLAYING=3;public int encoding=2,playState=3;
 public boolean offload=false;public int getAudioFormat(){return encoding;}
 public int getPlayState(){throw new AssertionError("Blocking play-state getter must not be used");}
 public boolean isOffloadedPlayback(){return offload;}
 public int write(byte[] a,int o,int n){return n;}public int write(byte[] a,int o,int n,int m){return n;}
 public int write(short[] a,int o,int n){return n;}public int write(short[] a,int o,int n,int m){return n;}
 public int write(float[] a,int o,int n,int m){return n;}
 public int write(ByteBuffer a,int n,int m){return n;}
 public int write(ByteBuffer a,int n,int m,long timestamp){return n;}}""",
    "io/github/libxposed/api/XposedInterface.java": """package io.github.libxposed.api;
import java.util.List;
public interface XposedInterface {interface Hooker {Object intercept(Chain c)throws Throwable;}
 interface Chain {Object proceed()throws Throwable;Object getThisObject();List<Object> getArgs();}}""",
    "com/os4/musiccover/Xp.java": """package com.os4.musiccover;
import java.lang.reflect.*;import java.util.*;import io.github.libxposed.api.XposedInterface;
final class Xp {static final Map<Method,XposedInterface.Hooker> hooks=new LinkedHashMap<>();
 static void hook(Method m,XposedInterface.Hooker h){hooks.put(m,h);}
 interface Original {Object run()throws Throwable;}
 static Object invoke(Method m,Object target,Original original,Object... args)throws Throwable{
  XposedInterface.Hooker hook=hooks.get(m);if(hook==null)return original.run();
  return hook.intercept(new XposedInterface.Chain(){public Object proceed()throws Throwable{return original.run();}
   public Object getThisObject(){return target;}public List<Object> getArgs(){return Arrays.asList(args);}});}
}""",
    "com/os4/musiccover/PlaybackPcmHookTest.java": """package com.os4.musiccover;
import android.content.*;import android.content.pm.*;import android.media.AudioTrack;
import android.os.Handler;import android.os.SystemClock;import java.lang.reflect.*;import java.nio.*;
public class PlaybackPcmHookTest {
 static final String PLAYER="com.tencent.qqmusic",SYS="com.android.systemui";
 static final String CONTROL="com.os4.musiccover.PCM_LEASE",ENERGY="com.os4.musiccover.PCM_ENERGY";
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static long field(String n)throws Exception{Field f=PlaybackPcmCapture.class.getDeclaredField(n);f.setAccessible(true);return f.getLong(null);}
 static Intent lease(long session,long until,long revision){return new Intent(CONTROL).setPackage(PLAYER)
  .putExtra("session",session).putExtra("until",until).putExtra("revision",revision);}
 static void near(double v,long energy){check(Math.abs(v-PlaybackAudioState.rms(energy))<.000001,"energy "+v);}
 public static void main(String[] args)throws Throwable{
  if(args[0].equals("player"))player();else if(args[0].equals("systemui"))systemui();
  else if(args[0].equals("verification"))verification();else rejected();
  System.out.println("PlaybackPcmHookTest "+args[0]+": "+checks+" checks passed");}
 static void player()throws Throwable{
  Context ctx=new Context(PLAYER);PlaybackPcmCapture.install(SYS);
  check(Xp.hooks.isEmpty(),"SystemUI audio not hooked");PlaybackPcmCapture.install(PLAYER);
  Method attach=android.app.Application.class.getDeclaredMethod("attach",Context.class);
  Xp.invoke(attach,new android.app.Application(),()->null,ctx);
  check(Xp.hooks.size()==8,"all seven real write signatures installed");
  check("android.permission.STATUS_BAR".equals(ctx.receiverPermissions.get(0)),"control signature permission");
  AudioTrack track=new AudioTrack();short[] pcm={123,16384,32767};
  Method legacy=AudioTrack.class.getDeclaredMethod("write",short[].class,int.class,int.class);
  Method modern=AudioTrack.class.getDeclaredMethod("write",short[].class,int.class,int.class,int.class);
  Object result=Xp.invoke(legacy,track,()->2,pcm,1,2);
  check(result.equals(2)&&field("mailboxVersion")==0,"inactive original preserved");
  ctx.deliver(30000,SYS,lease(7,2400,1));check(field("playerUntil")==0,"spoofed UID");
  ctx.deliver(1000,"evil",lease(7,2400,1));check(field("playerUntil")==0,"spoofed package");
  ctx.deliver(1000,SYS,lease(7,2600,1));check(field("playerUntil")==0,"oversized lease");
  ctx.deliver(1000,SYS,lease(7,2400,1).setPackage(SYS));check(field("playerUntil")==0,"wrong target");
  ctx.deliver(1000,SYS,lease(7,2400,1));check(field("playerUntil")==2400,"authorized lease");
  int[] calls={0};result=Xp.invoke(legacy,track,()->{calls[0]++;return Xp.invoke(modern,track,()->1,pcm,1,2,0);},pcm,1,2);
  check(result.equals(1)&&calls[0]==1,"exactly once and partial return preserved");
  check(field("mailboxVersion")==2,"nested write measured once");near(.5,field("mailboxEnergy"));
  Handler.runTasks();check(ctx.sent.size()==1,"worker publishes scalar only");
  check(SYS.equals(ctx.sent.get(0).getPackage()),"explicit data target");
  check("android.permission.STATUS_BAR".equals(ctx.sentPermissions.get(0)),"privileged data recipient");
  Xp.invoke(legacy,track,()->2,pcm,1,2);check(field("mailboxVersion")==2,"75ms throttle");
  SystemClock.now+=75;RuntimeException original=new RuntimeException("original");
  try{Xp.invoke(legacy,track,()->{throw original;},pcm,1,2);throw new AssertionError();}
  catch(RuntimeException e){check(e==original,"original exception identity");}
  check(field("mailboxVersion")==2,"failed write ignored");
  check(Xp.invoke(legacy,track,()->-3,pcm,1,2).equals(-3),"error return preserved");
  Xp.invoke(legacy,track,()->0,pcm,1,2);check(field("mailboxVersion")==2,"zero/error accepted nothing");
  // Deliberately invalid callback receiver: exception is swallowed, result is unchanged.
  check(Xp.invoke(legacy,new Object(),()->1,pcm,1,2).equals(1),"callback exception isolated");
  Xp.invoke(legacy,track,()->1,pcm,1,2);check(field("mailboxVersion")==4,"guard restored after exception");
  ByteBuffer buffer=ByteBuffer.allocateDirect(8);buffer.put(2,(byte)0).put(3,(byte)64);
  buffer.position(2).limit(6).mark();track.encoding=2;SystemClock.now+=75;
  Method bb=AudioTrack.class.getDeclaredMethod("write",ByteBuffer.class,int.class,int.class,long.class);
  Xp.invoke(bb,track,()->{buffer.position(4);return 2;},buffer,4,0,123L);
  check(buffer.position()==4&&buffer.limit()==6,"only original changes buffer cursor");
  near(.5,field("mailboxEnergy"));buffer.reset();check(buffer.position()==2,"mark retained");
  // Float-array and byte-array paths use only successfully accepted prefixes.
  SystemClock.now+=75;track.encoding=4;
  Method floats=AudioTrack.class.getDeclaredMethod("write",float[].class,int.class,int.class,int.class);
  Xp.invoke(floats,track,()->1,new float[]{9,.25f,1},1,2,1);near(.25,field("mailboxEnergy"));
  SystemClock.now+=75;track.encoding=21;
  Method bytes=AudioTrack.class.getDeclaredMethod("write",byte[].class,int.class,int.class,int.class);
  Xp.invoke(bytes,track,()->3,new byte[]{99,0,0,64,0,0,(byte)128},1,6,0);near(.5,field("mailboxEnergy"));
  SystemClock.now+=75;track.encoding=999;long before=field("mailboxVersion");
  Xp.invoke(bytes,track,()->3,new byte[]{0,0,64},0,3,0);check(field("mailboxVersion")==before,"compressed format ignored");
  SystemClock.now+=75;track.encoding=2;track.offload=true;
  Xp.invoke(legacy,track,()->1,pcm,1,2);check(field("mailboxVersion")==before,"offloaded playback ignored");
  track.offload=false;track.playState=2;ctx.deliver(1000,SYS,lease(7,0,2));
  Xp.invoke(legacy,track,()->1,pcm,1,2);
  check(field("mailboxVersion")==before,"renderer pause release stops capture");track.playState=3;
  check(field("playerUntil")==0,"release");
  ctx.deliver(1000,SYS,lease(7,2400,1));check(field("playerUntil")==0,"old control rejected");
  Handler.runTasks();check(Handler.pending()==0,"idle worker has no polling");
  ctx.deliver(1000,SYS,lease(8,SystemClock.now+100,3));SystemClock.now+=101;
  Xp.invoke(legacy,track,()->1,pcm,1,2);check(field("mailboxVersion")==before,"lease expiry stops sampling");
  Handler.runTasks();check(Handler.pending()==0,"expiry stops publishing");
 }
 static Intent data(long token,long at,long energy){return new Intent(ENERGY).setPackage(SYS)
  .putExtra("session",token).putExtra("at",at).putExtra("energy",energy);}
 static void systemui()throws Throwable{
  android.os.Process.uid=1000;Context ctx=new Context(SYS);
  check(PlaybackPcmCapture.setActive(ctx,PLAYER,true),"SystemUI activation");
  check(Xp.hooks.isEmpty(),"SystemUI never installs audio hooks");
  check(ctx.sent.size()==1&&PLAYER.equals(ctx.sent.get(0).getPackage()),"targeted control");
  long token=ctx.sent.get(0).getLongExtra("session",0),energy=PlaybackAudioState.pack(.25f,.5f);
  PlaybackPcmCapture.setActive(ctx,PLAYER,true);check(ctx.sent.size()==1,"control throttled");
  ctx.deliver(30000,PLAYER,data(token,1000,energy));near(0,PlaybackPcmCapture.sample());
  ctx.deliver(20000,"evil",data(token,1000,energy));near(0,PlaybackPcmCapture.sample());
  ctx.deliver(20000,PLAYER,data(token+1,1000,energy));near(0,PlaybackPcmCapture.sample());
  ctx.deliver(20000,PLAYER,data(token,1001,energy));near(0,PlaybackPcmCapture.sample());
  ctx.deliver(20000,PLAYER,data(token,1000,energy).setPackage(PLAYER));near(0,PlaybackPcmCapture.sample());
  ctx.deliver(20000,PLAYER,data(token,1000,energy));near(.25,PlaybackPcmCapture.sample());
  int[] callbacks={0};PlaybackPcmCapture.setOnSample(()->{
   check(Handler.executingLooper==android.os.Looper.getMainLooper(),"callback uses main handler");
   callbacks[0]++;
  });
  ctx.deliver(20000,PLAYER,data(token,1000,energy));
  ctx.deliver(20000,PLAYER,data(token,1000,energy));
  check(callbacks[0]==0,"callback deferred, not inline in receiver");
  check(Handler.pending()==1,"sample notifications coalesced");
  Handler.runTasks();check(callbacks[0]==1,"one callback for burst");
  ctx.deliver(20000,PLAYER,data(token,1000,energy));
  Handler.runTasks();check(callbacks[0]==2,"pending reset permits next callback");
  ctx.deliver(20000,PLAYER,data(token,1000,energy));PlaybackPcmCapture.setOnSample(null);
  Handler.runTasks();check(callbacks[0]==2,"cleared listener ignores queued callback");
  check(Handler.pending()==0,"callback queue drained");
  PlaybackPcmCapture.setOnSample(()->callbacks[0]++);
  ctx.deliver(30000,PLAYER,data(token,1000,energy));
  check(Handler.pending()==0,"unauthenticated sample does not wake renderer");
  PlaybackPcmCapture.setOnSample(null);
  SystemClock.now=1351;near(0,PlaybackPcmCapture.sample());
  ctx.pm.packages.get(PLAYER).signingInfo.signers=new Signature[]{new Signature(9)};
  ctx.deliver(20000,PLAYER,data(token,1351,energy));near(0,PlaybackPcmCapture.sample());
  ctx.pm.packages.get(PLAYER).signingInfo.signers=new Signature[]{new Signature(1)};
  ctx.deliver(20000,PLAYER,data(token,1351,energy));near(.25,PlaybackPcmCapture.sample());
  PlaybackPcmCapture.stop(ctx);near(0,PlaybackPcmCapture.sample());
  check(ctx.sent.get(ctx.sent.size()-1).getLongExtra("until",-1)==0,"immediate remote release");
  ctx.deliver(20000,PLAYER,data(token,1351,energy));near(0,PlaybackPcmCapture.sample());
  check(!PlaybackPcmCapture.setActive(ctx,"evil",true),"allowlist only");
  check(PlaybackPcmCapture.setActive(ctx,PLAYER,true),"reactivation");
  long next=ctx.sent.get(ctx.sent.size()-1).getLongExtra("session",0);
  check(next!=token,"new session prevents queued prior data");
  SystemClock.now=2852;ctx.deliver(20000,PLAYER,data(next,2852,energy));near(0,PlaybackPcmCapture.sample());
  check(ctx.sent.get(ctx.sent.size()-1).getLongExtra("until",0)==2851,"no autonomous renewal");
  check(PlaybackPcmCapture.setActive(ctx,PLAYER,true),"fresh lease after expiry");
  PlaybackPcmCapture.stop(null);near(0,PlaybackPcmCapture.sample());
 }
 static long activeToken(Context ctx){return ctx.sent.get(ctx.sent.size()-1).getLongExtra("session",0);}
 static void verification()throws Throwable{
  android.os.Process.uid=1000;Context ctx=new Context(SYS);
  check(PlaybackPcmCapture.setActive(ctx,PLAYER,true),"verification activation");
  long token=activeToken(ctx),energy=PlaybackAudioState.pack(.25f,.5f);
  int[] callbacks={0};PlaybackPcmCapture.setOnSample(()->callbacks[0]++);
  java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
  java.util.concurrent.CountDownLatch finish=new java.util.concurrent.CountDownLatch(1);
  java.util.concurrent.CountDownLatch stopped=new java.util.concurrent.CountDownLatch(1);
  boolean[] monitorHeld={true};
  ctx.pm.beforeLookup=()->{
   monitorHeld[0]=Thread.holdsLock(PlaybackPcmCapture.class);entered.countDown();
   try{if(!finish.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("lookup timed out");}
   catch(InterruptedException e){throw new AssertionError(e);}
  };
  Thread receiver=new Thread(()->ctx.deliver(20000,PLAYER,data(token,1000,energy)));
  Thread renderer=new Thread(()->{PlaybackPcmCapture.stop(ctx);stopped.countDown();});
  receiver.start();boolean reached=false,stopCompleted=false;
  try{
   reached=entered.await(2,java.util.concurrent.TimeUnit.SECONDS);
   if(reached){renderer.start();stopCompleted=stopped.await(2,java.util.concurrent.TimeUnit.SECONDS);}
  }finally{finish.countDown();receiver.join(2000);if(reached)renderer.join(2000);}
  check(reached,"receiver reached PackageManager verification");
  check(!monitorHeld[0],"PackageManager lookup outside renderer monitor");
  check(stopCompleted,"renderer stop completes while PackageManager is blocked");
  check(!receiver.isAlive()&&!renderer.isAlive(),"concurrency fixture threads terminate");
  near(0,PlaybackPcmCapture.sample());
  check(Handler.pending()==0&&callbacks[0]==0,"released sample cannot notify renderer");

  check(PlaybackPcmCapture.setActive(ctx,PLAYER,true),"reactivate before source race");
  long oldToken=activeToken(ctx);String other="com.netease.cloudmusic";ctx.pm.add(other,20001);
  boolean[] switched={false};
  ctx.pm.beforeLookup=()->switched[0]=PlaybackPcmCapture.setActive(ctx,other,true);
  ctx.deliver(20000,PLAYER,data(oldToken,SystemClock.now,energy));
  check(switched[0],"selection can switch during verification");
  near(0,PlaybackPcmCapture.sample());check(Handler.pending()==0,"prior source cannot notify");
  long otherToken=activeToken(ctx);
  ctx.deliver(20001,other,data(otherToken,SystemClock.now,energy));near(.25,PlaybackPcmCapture.sample());
  Handler.runTasks();check(callbacks[0]==1,"new source still wakes renderer on main");

  PlaybackPcmCapture.stop(ctx);check(PlaybackPcmCapture.setActive(ctx,PLAYER,true),"reactivate before session race");
  long prior=activeToken(ctx);
  ctx.pm.beforeLookup=()->{PlaybackPcmCapture.stop(ctx);PlaybackPcmCapture.setActive(ctx,PLAYER,true);};
  ctx.deliver(20000,PLAYER,data(prior,SystemClock.now,energy));
  check(activeToken(ctx)!=prior,"same-package restart has a new session");
  near(0,PlaybackPcmCapture.sample());check(Handler.pending()==0,"prior session cannot notify");

  long current=activeToken(ctx);long before=SystemClock.now;
  ctx.pm.beforeLookup=()->{SystemClock.now++;PlaybackPcmCapture.setActive(ctx,PLAYER,true);};
  ctx.deliver(20000,PLAYER,data(current,before,energy));
  near(0,PlaybackPcmCapture.sample());check(Handler.pending()==0,"lease renewal racing lookup rejects old snapshot");
  ctx.deliver(20000,PLAYER,data(current,SystemClock.now,energy));near(.25,PlaybackPcmCapture.sample());
  Handler.runTasks();check(callbacks[0]==2,"renewed lease accepts a subsequent verified sample");

  PlaybackPcmCapture.stop(ctx);PlaybackPcmCapture.setActive(ctx,PLAYER,true);
  long fresh=activeToken(ctx);before=SystemClock.now;
  ctx.pm.beforeLookup=()->SystemClock.now+=PlaybackAudioState.STALE_MS+1;
  ctx.deliver(20000,PLAYER,data(fresh,before,energy));
  near(0,PlaybackPcmCapture.sample());check(Handler.pending()==0,"freshness rechecked after slow lookup");
  ctx.pm.beforeLookup=()->SystemClock.now+=PlaybackAudioState.LEASE_MS;
  ctx.deliver(20000,PLAYER,data(fresh,SystemClock.now,energy));
  near(0,PlaybackPcmCapture.sample());check(Handler.pending()==0,"expiry during lookup prevents accept and notification");
 }
 static void rejected(){
  android.os.Process.uid=1000;Context ctx=new Context(SYS);ctx.pm.platform=false;
  check(!PlaybackPcmCapture.setActive(ctx,PLAYER,true),"nonplatform SystemUI fails closed");
  ctx.pm.platform=true;ctx.permission=-1;
  check(!PlaybackPcmCapture.setActive(ctx,PLAYER,true),"missing signature permission fails closed");
  ctx.permission=0;ctx.pm.packages.get(PLAYER).signingInfo=null;
  check(!PlaybackPcmCapture.setActive(ctx,PLAYER,true),"unsigned player fails closed");
  check(ctx.sent.isEmpty(),"no unauthorized control");
 }
}
""",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, help="JDK directory (otherwise uses PATH)")
    args = parser.parse_args()
    suffix = ".exe" if os.name == "nt" else ""
    javac = str(args.java_home / "bin" / ("javac" + suffix)) if args.java_home else shutil.which("javac")
    java = str(args.java_home / "bin" / ("java" + suffix)) if args.java_home else shutil.which("java")
    if not javac or not java:
        parser.error("A JDK is required; supply --java-home")
    root = Path(__file__).resolve().parent.parent
    with tempfile.TemporaryDirectory(prefix="musiccover-pcm-") as directory:
        work = Path(directory)
        sources = []
        for name, content in STUBS.items():
            path = work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        source = root / "app/src/main/java/com/os4/musiccover"
        sources.extend(source / name for name in ("PlaybackAudioState.java", "PlaybackPcmCapture.java"))
        sources.append(root / "app/src/test/java/com/os4/musiccover/PlaybackAudioStateTest.java")
        classes = work / "classes"
        classes.mkdir()
        subprocess.run([javac, "-encoding", "UTF-8", "-d", str(classes), *map(str, sources)], check=True)
        subprocess.run([java, "-cp", str(classes), "com.os4.musiccover.PlaybackAudioStateTest"], check=True)
        for mode in ("player", "systemui", "rejected", "verification"):
            subprocess.run([java, "-cp", str(classes), "com.os4.musiccover.PlaybackPcmHookTest", mode], check=True)


if __name__ == "__main__":
    main()
