# interruptible-getstrln
Interruptible ZIO getStrLn POC

## Three versions

* _JRuby_ - uses reflection to get stdin file channel.
* _FileDescriptor_ - opens stdin file channel directly, doesn't use reflection.
* _Polling_ - polls stdin using `available()` in a hot loop, yielding to other threads using `Thread.sleep`.

## Testing
```
sbt pack
target/pack/bin/test "jruby"
target/pack/bin/test "fileDescriptor"
target/pack/bin/test "polling"
```
