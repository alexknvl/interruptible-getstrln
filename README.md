# interruptible-getstrln
Interruptible ZIO getStrLn POC

## Three versions

* JRuby - uses reflection to get stdin file channel
* FileDescriptor - opens stdin file channel directly, doesn't use reflection
* Polling - polls stdin using `available()` in a hot loop, yielding to other threads using `Thread.sleep`.

## Testing
```
sbt pack
target/pack/bin/test "jruby"
target/pack/bin/test "fileDescriptor"
target/pack/bin/test "polling"
```
