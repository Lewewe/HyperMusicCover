#!/usr/bin/env python3
"""Exercise actual matching policies with JVM adapters for Android-only dependencies.

The adapters leave ASCII strings unchanged and do not emulate transliteration or artist aliases.
"""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = root / 'app/src/main/java/com/os4/musiccover'
with tempfile.TemporaryDirectory(prefix='hmc-original-match-') as temp:
    work = Path(temp)
    package = work / 'com/os4/musiccover'
    package.mkdir(parents=True)
    for name in ('LyricMatch.java', 'OriginalLyricMatch.java', 'LyricSearchAliases.java'):
        (package / name).write_text((source / name).read_text())
    (package / 'Adapters.java').write_text('''package com.os4.musiccover;
final class LockLyrics {
 static final int SEARCH_ORIGINAL=0, SEARCH_EXTENDED=1;
 static int sSearchMode=SEARCH_ORIGINAL;
}
final class TrackName {
 static String[] aliased(String s) { return null; }
}
final class NcmLyrics {
 static String folded(String s) { return s == null ? "" : s; }
 static final class Query {
  final String title,artist,album,qqId; final long durationMs;
  Query(String t,String a,String b,long d) { title=t;artist=a;album=b;durationMs=d;qqId=null; }
  String key() { return title+"|"+artist+"|"+album+"|"+durationMs; }
  Query withNames(String t,String a) { return new Query(t,a,album,durationMs); }
 }
}
''')
    (package / 'ModeMatchAudit.java').write_text('''package com.os4.musiccover;
import java.util.Arrays;
public final class ModeMatchAudit {
 public static void main(String[] args) {
  int checks=0;
  for (String artist : new String[]{"Artist","Artist & Guest","Artist feat. Guest","Artist / Guest"}) {
   NcmLyrics.Query q=new NcmLyrics.Query("Song",artist,"Album",200000L);
   LockLyrics.sSearchMode=0;
   LyricMatch.Wanted actual=new LyricMatch.Wanted(q);
   OriginalLyricMatch.Wanted expected=new OriginalLyricMatch.Wanted(q);
   if(actual.multiCredit()!=expected.multiCredit()) throw new AssertionError("credits: "+artist);
   checks++;
   for (String title : new String[]{"Song","Song Extended","Other","Song (Live)"}) {
    for(long duration : new long[]{0L,200000L,204000L,210000L}) {
     LyricMatch.Candidate c=new LyricMatch.Candidate("id",title,"Artist","Album",duration,null);
     OriginalLyricMatch.Candidate o=new OriginalLyricMatch.Candidate("id",title,"Artist","Album",duration,null);
     int score=LyricMatch.score(c,actual), original=OriginalLyricMatch.score(o,expected);
     if(score!=original) throw new AssertionError("score: "+title);
     LyricMatch.Pick pick=LyricMatch.best(Arrays.asList(c),actual);
     OriginalLyricMatch.Pick old=OriginalLyricMatch.best(Arrays.asList(o),expected);
     if(pick.score!=old.score || pick.passes()!=old.passes() || pick.artistMatched!=old.artistMatched)
      throw new AssertionError("pick: "+title);
     checks+=2;
    }
   }
  }
  NcmLyrics.Query q=new NcmLyrics.Query("Song","Artist feat. Guest","Album",200000L);
  LockLyrics.sSearchMode=0;
  if(new LyricMatch.Wanted(q).multiCredit()) throw new AssertionError("Original feature credit");
  LockLyrics.sSearchMode=1;
  if(!new LyricMatch.Wanted(q).multiCredit()) throw new AssertionError("Extended feature credit");
  System.out.println("Original matching audit: "+(checks+2)+" checks passed");
 }
}
''')
    subprocess.run(['javac', '-d', str(work), *map(str, package.glob('*.java'))], check=True)
    subprocess.run(['java', '-cp', str(work), 'com.os4.musiccover.ModeMatchAudit'], check=True)
