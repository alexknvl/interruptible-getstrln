# Interruptible `getStrLn` Proof of Concept

## Implementations

* _Old_ - Uses default `Console` implementation.
* _JRuby_ - Uses reflection to get stdin file channel. Channel's `read` operation is _interruptible_ using `Thread.interrupt()`.
* _FileDescriptor_ - Opens stdin file channel directly, doesn't use reflection.
* _Polling_ - Polls stdin using `available()` in a hot loop, yielding to other threads using `Thread.sleep`. In theory it could use `ZIO.sleep` instead and block asynchronously, but that would require replicating `new BufferedReader(new InputStreamReader(...))` logic.
* _Polling2_ - Uses proposed solution from https://github.com/zio/zio/issues/780#issuecomment-731782726.
* _BrokenFast_ - Fastest possible reader.
* _Fast_ - Buffered interruptible solution.
* _Current_ - Current implementation of Console2 (Fast).

## Two scenarios

* _Interrupt_ - https://github.com/zio/zio/issues/780
* _Pipe_ - https://github.com/zio/zio/issues/3417

## Testing
```
sbt pack

target/pack/bin/test old interrupt                        # BAD, doesn't terminate
target/pack/bin/test jruby interrupt
target/pack/bin/test fileDescriptor interrupt
target/pack/bin/test polling interrupt                    # BAD, doesn't terminate
target/pack/bin/test polling2 interrupt
target/pack/bin/test brokenFast interrupt                 # BAD, doesn't terminate
target/pack/bin/test fast interrupt

cat test.txt | target/pack/bin/test old pipe              # BAD, totally broken
cat test.txt | target/pack/bin/test jruby pipe            # GOOD, 63.3s
cat test.txt | target/pack/bin/test fileDescriptor pipe   # GOOD, 65.0s
cat test.txt | target/pack/bin/test polling interrupt     # BAD, doesn't stop on eof
cat test.txt | target/pack/bin/test polling2 interrupt    # BAD, doesn't stop on eof, extremely slow
cat test.txt | target/pack/bin/test brokenFast interrupt  # GOOD, 12.7s
cat test.txt | target/pack/bin/test fast interrupt        # GOOD, 20.8s
```
