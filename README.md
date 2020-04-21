# Interruptible `getStrLn` Proof of Concept

## Four implementations

* _Old_ - Uses default `Console` implementation.
* _JRuby_ - Uses reflection to get stdin file channel. Channel's `read` operation is _interruptible_ using `Thread.interrupt()`.
* _FileDescriptor_ - Opens stdin file channel directly, doesn't use reflection.
* _Polling_ - Polls stdin using `available()` in a hot loop, yielding to other threads using `Thread.sleep`. In theory it could use `ZIO.sleep` instead and block asynchronously, but that would require replicating `new BufferedReader(new InputStreamReader(...))` logic.

## Two scenarios

* _Interrupt_ - https://github.com/zio/zio/issues/780
* _Pipe_ - https://github.com/zio/zio/issues/3417

## Testing
```
sbt pack

target/pack/bin/test old interrupt                   # BAD, doesn't terminate
target/pack/bin/test jruby interrupt
target/pack/bin/test fileDescriptor interrupt
target/pack/bin/test polling interrupt

cat test.txt | target/pack/bin/test old pipe         # BAD, reads only 1 line
cat test.txt | target/pack/bin/test jruby pipe
cat test.txt | target/pack/bin/test fileDescriptor pipe
cat test.txt | target/pack/bin/test polling pipe     # BAD, doesn't stop on eof
```
