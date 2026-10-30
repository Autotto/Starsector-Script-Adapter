# S3A: Starsector-Script-Adapter

Notice: AI Slop

## Intro
**S**tar**s**ector-**S**cript-**A**dapter AKA **`S3A`** is A pure AI Slop, Generated with Human Supervising.  
this IS NOT RANDOM SHIT BY WISH CODING, we have known we are doing what.

This AI Shit can make you Adapting Janino `2.x` with Custom Compiler Backend,   
And allow you **delegating classDefine to other Classloader** (we designed this cuz we need fix some cl hacks, and we are not using javaagent to build CoreMods)


We're only wiring interface and Janino ABI facade, ECJ Backend is powered by **`ChatGPT™`**.  
and this meaning Script Compiled Slowed than Janino *`1.75x`* more, but at last **WE HAVE  JAVA 17 SYNTAX SUPPORTS**.

Wow.

And it also Compat with `Fast Rendering`, but cuz ECJ Backend Impl issues, `MultiThreadedJaninoClassLoader` causes more memory and startup overhead, making it worse than Janino version, but **WE HAVE JAVA 17 SYNTAX SUPPORTS** now.

To optimize performance , we impl some caches in this AI Slop, those cache may helpful to save times, but it will make more memory use.

you can enable those features by system properties
```
-Dstarsector.scriptAdapter.sourceLru=true
-Dstarsector.scriptAdapter.compiledReuse=true
```
btw, those features also coded by **`ChatGPT™`** , lmao.

and last...  
Alex ,we think we need Groovy.