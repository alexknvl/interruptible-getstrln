# Interruptible `getStrLn` Proof of Concept

## Three versions

* _JRuby_ - Uses reflection to get stdin file channel. Channel's `read` operation is _interruptible_.
* _FileDescriptor_ - Opens stdin file channel directly, doesn't use reflection.
* _Polling_ - Polls stdin using `available()` in a hot loop, yielding to other threads using `Thread.sleep`. In theory it could use `ZIO.sleep` instead and block asynchronously, but that would require replicating `new BufferedReader(new InputStreamReader(...))` logic.

## Testing
```
sbt pack
target/pack/bin/test "jruby"
target/pack/bin/test "fileDescriptor"
target/pack/bin/test "polling"
```
