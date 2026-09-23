# ROS2-to-AdHoc — ROS 2 interfaces → AdHoc protocol description

> One of the [**converters to AdHoc protocol**](https://github.com/AdHoc-Protocol#converters-to-adhoc-protocol).
> Take a protocol you already have, get an [AdHoc](https://github.com/AdHoc-Protocol/AdHoc-protocol) description,
> open it in the Observer. The result is a starting point you refine by hand, not a finished protocol.

Converts ROS 2 interface definition files (`.msg`, `.srv`, `.action`) of any number of ROS packages into one
[AdHoc](https://github.com/AdHoc-Protocol) protocol-description `.cs` file. Topics become packs, services become
RPC calls, actions become stateful actors. The project is self-contained: Java 17, no dependencies, its own copy
of the emitter helpers in `src/org/unirail/adhoc/`.

The output is meant to read as AdHoc, not as ROS spelled in C#. Concretely: `builtin_interfaces/Time` becomes the
native `DateTime` and `builtin_interfaces/Duration` a `: Duration` alias instead of two raw integer fields; the
ceilings AdHoc needs for what ROS leaves unbounded are stated once in `_DefaultMaxLengthOf`, so a `[D(...)]` on a
field always means the ROS file stated a real bound. Anything the converter could not carry over is marked with a
`// DROPPED:` comment at the place it was dropped.

## Before and after

`sensor_msgs/msg/NavSatFix.msg`, 45 lines — a ROS 2 protocol has no single file of 500-1000 lines, because it is
a package of many small ones (`sensor_msgs` is 28 files, 728 lines together); this is the most recognisable
message of the most recognisable package.
[source](samples/ros2_interfaces/sensor_msgs/msg/NavSatFix.msg) · [result](AdHoc/ros2_interfaces.cs)

```python
# Navigation Satellite fix for any Global Navigation Satellite System
#
# Specified using the WGS 84 reference ellipsoid

# header.stamp specifies the ROS time for this measurement (the
#        corresponding satellite time may be reported using the
#        sensor_msgs/TimeReference message).
std_msgs/Header header

# Satellite fix status information.
NavSatStatus status

# Latitude [degrees]. Positive is north of equator; negative is south.
float64 latitude
...
# Position covariance [m^2] defined relative to a tangential plane
# through the reported position. The components are East, North, and
# Up (ENU), in row-major order.
float64[9] position_covariance

uint8 COVARIANCE_TYPE_UNKNOWN = 0
uint8 COVARIANCE_TYPE_APPROXIMATED = 1
...
```

```csharp
public class NavSatFix {
    /**
    header.stamp specifies the ROS time for this measurement (the
    corresponding satellite time may be reported using the
    sensor_msgs/TimeReference message).
    */
    std_msgs.msg.Header header;
    /**
    Satellite fix status information.
    */
    sensor_msgs.msg.NavSatStatus status;
    /**
    Latitude [degrees]. Positive is north of equator; negative is south.
    */
    double latitude;
    // ...
    [D(9)] double[] position_covariance;
    const byte COVARIANCE_TYPE_UNKNOWN = 0;
    const byte COVARIANCE_TYPE_APPROXIMATED = 1;
    // ...
}

public class Header {                          // std_msgs/Header, referenced above
    DateTime stamp; // was builtin_interfaces/Time (int32 sec + uint32 nanosec); ns resolution dropped
    string frame_id;
}
```

The header's two-integer ROS timestamp is gone: `builtin_interfaces/Time` is a wall-clock instant, and AdHoc has
one, so `Header.stamp` is a `DateTime` and the `sec`/`nanosec` pair never reaches the wire. `float64[9]` keeps its
constant length as `[D(9)]`, the `uint8` constants become `const byte`, and the `#` blocks become doc comments the
agent pools with the Dashboard line, so `KeepDoc` filters can route on them. A service goes further — the whole
request/response pair collapses into one line inside the connection:

```csharp
(L____________, nav_msgs.srv.GetPlan_Response) nav_msgs_GetPlan(nav_msgs.srv.GetPlan_Request req);
```

## Links

| What                                              | Where                                                                    |
|:--------------------------------------------------|:-------------------------------------------------------------------------|
| Interface definition language (.msg/.srv/.action) | https://design.ros2.org/articles/legacy_interface_definition.html        |
| rosidl (reference parser and generators)          | https://github.com/ros2/rosidl                                           |
| Samples: common interfaces                        | https://github.com/ros2/common_interfaces                                |
| Samples: client-library interfaces                | https://github.com/ros2/rcl_interfaces                                   |
| Samples: examples with .srv / .action             | https://github.com/ros2/example_interfaces                               |
| Samples: UUID (referenced by action_msgs)         | https://github.com/ros2/unique_identifier_msgs                           |
| Samples: edge cases (installed as `test_msgs`)    | https://github.com/ros2/test_interface_files                             |
| AdHoc protocol description format                 | https://github.com/AdHoc-Protocol/AdHoc-protocol                          |

## Layout

| Path                                  | Contents                                                                 |
|:--------------------------------------|:-------------------------------------------------------------------------|
| `src/org/unirail/ROS2ToAdHoc.java`    | The converter                                                            |
| `src/org/unirail/adhoc/*.java`        | Local emitter helpers (naming rules, doc escaping, dashboard, hosts)     |
| `fetch-samples.sh`                    | Shallow-clones the five repositories above into `samples/ros2_interfaces/<pkg>/{msg,srv,action}` |
| `samples/ros2_interfaces/`            | 22 packages: 205 `.msg`, 34 `.srv`, 3 `.action`                          |
| `build.sh`                            | Compiles and converts the samples into `AdHoc/`                          |
| `validate.sh`                         | Runs AdHocAgent in local parse-only mode over `AdHoc/*.cs`               |
| `AdHoc/ros2_interfaces.cs`            | Generated descriptor (+ `ros2_interfaces.branches.txt`, the agent's FSM dump) |

## Usage

```bash
./fetch-samples.sh                     # needs git
./build.sh                             # javac + run: samples/ros2_interfaces → AdHoc/ros2_interfaces.cs
./validate.sh AdHoc                    # every file must print OK

# by hand
javac -encoding UTF-8 --release 17 -d out src/org/unirail/adhoc/*.java src/org/unirail/*.java
java -Dfile.encoding=UTF-8 -cp out org.unirail.ROS2ToAdHoc <folder with ROS packages> [output folder]
```

The input is a folder of ROS packages (`<pkg>/msg/*.msg`, `<pkg>/srv/*.srv`, `<pkg>/action/*.action`); a folder
that itself contains `msg/`, `srv/` or `action/` is treated as a single package. All packages of one run are one
bundle (they reference each other, e.g. `std_msgs/Header`), so a single `<input folder name>.cs` is written.
Output folder defaults to `<cwd>/AdHoc`.

## What the generated file contains

```csharp
namespace org.ros2 {
    /** <see cref='action_msgs.msg.GoalInfo'/> … */        // Packs Inventory, every pack, ids left to the agent
    public interface ros2_interfaces {
        enum _DefaultMaxLengthOf { Arrays = 65_535, Maps = 65_535, Sets = 65_535, Strings = 65_535, }
        public struct std_msgs {                            // one container per package …
            public struct msg { public class Header { … } … }   // … and per kind, mirroring <pkg>/msg/Name
        }
        public struct example_interfaces {
            public struct srv    { public class AddTwoInts_Request { … } public class AddTwoInts_Response { … } }
            public struct action { public class Fibonacci_Goal { … } … _Result, _Feedback }
        }
        struct Client : Host { }  struct Server : Host { }
        interface Communication : Connects<Client, Server> {
            [_____lr_____<@ros2_interfaces>(KeepName: @"\.msg\.")]     // topics: every *.msg pack, both ways
            struct Topics { }
            (L____________, example_interfaces.srv.AddTwoInts_Response) example_interfaces_AddTwoInts(example_interfaces.srv.AddTwoInts_Request req);
            interface example_interfaces_Fibonacci : Actor {              // action
                int MaxActiveInstances => UNLIMITED;
                [L____________<Executing, example_interfaces.action.Fibonacci_Goal>]  struct SendGoal { }
                [____________r<example_interfaces.action.Fibonacci_Feedback>]
                [l____________<action_msgs.srv.CancelGoal_Request>]
                [____________R<End, example_interfaces.action.Fibonacci_Result>]      struct Executing { }
            }
        }
        public class ROS_Duration : Duration {                            // builtin_interfaces/Duration
            public long     max       => 2_147_483_647_000;
            public TimeSpan precision => TimeSpan.FromMilliseconds(1);
        }
        public class DefaultAttribute : Attribute { … }                  // metadata attribute
    }
}
```

- The package / kind containers are `struct`s (non-transmittable), so the branch filter `KeepName: @"\.msg\."`
  selects exactly the topic messages; service and action packs are only reachable through the RPC / actor
  declarations. A type reference `pkg/Name` (or bare `Name`) resolves to `pkg.msg.Name`.
- A service `pkg/Name.srv` becomes `pkg.srv.Name_Request` + `pkg.srv.Name_Response` and the RPC shorthand
  `(L____________, Response) pkg_Name(Request req);` — the Client calls, the Server answers, one transient actor per call.
- An action `pkg/Name.action` becomes `_Goal`, `_Result`, `_Feedback` packs and an `Actor` with two states: the
  Client sends the goal and the actor moves to `Executing`; there the Server streams feedback (non-transitional),
  the Client may send `action_msgs/CancelGoal_Request` (non-transitional), and the Server ends the actor with the
  result. Several goals may be in flight (`UNLIMITED`).

## Type mapping

| ROS 2                              | AdHoc / C#                          | Notes                                                        |
|:-----------------------------------|:------------------------------------|:-------------------------------------------------------------|
| `bool`                             | `bool`                              |                                                              |
| `byte`, `uint8`, `char`            | `byte`                              | `char` is an unsigned 8-bit value in ROS 2                   |
| `int8` / `int16` / `int32` / `int64` | `sbyte` / `short` / `int` / `long` |                                                              |
| `uint16` / `uint32` / `uint64`     | `ushort` / `uint` / `ulong`         |                                                              |
| `float32` / `float64`              | `float` / `double`                  |                                                              |
| `string`, `wstring`                | `string`                            | AdHoc strings are Unicode; the wide/narrow distinction is dropped |
| `string<=N`                        | `[D(+N)] string`                    | a real bound from the source                                 |
| unbounded `string`                 | `string`                            | no `[D]`: the ceiling comes from `_DefaultMaxLengthOf.Strings` |
| `T[N]`                             | `[D(N)] T[]`                        | constant length                                              |
| `T[<=N]`                           | `[D(N)] T[,,]`                      | list, at most N items                                        |
| `T[]`                              | `T[,,]`                             | no `[D]`: the ceiling comes from `_DefaultMaxLengthOf.Arrays` |
| `builtin_interfaces/Time`          | `DateTime`                          | AdHoc's native wall-clock type; the `sec`/`nanosec` pair is **not** emitted as a pack |
| `builtin_interfaces/Duration`      | `ROS_Duration` (`class … : Duration`) | elapsed time, bit-sized from `max` / `precision`; the raw pair is **not** emitted as a pack |
| any integer field                  | plain type, plus a `// physics:` comment where a hint exists | a `.msg` never states where a field's values sit, so no `[A]`/`[V]`/`[X]` is emitted — but the question is asked at the field, see [below](#varint-is-your-decision-asked-at-the-field) |
| `pkg/Name`, `Name`                 | `pkg.msg.Name`                      | sub-pack; an **empty** message becomes `bool` (presence flag), which is what AdHoc does anyway |
| `TYPE NAME=VALUE`                  | `const TYPE NAME = VALUE;`          | hex / binary / octal integers converted to decimal           |
| `TYPE name VALUE` (default)        | `[Default("VALUE")] TYPE name;`     | custom attribute, value kept as written in the file          |
| `# comment`                        | `/** … */` doc                      | block above the first field → message doc; others → field doc |

Names that are keywords in any AdHoc target language are renamed the way AdHocAgent does it
(`type` → `Type`); a field named like its own pack gets a numeric suffix.

## Varint is your decision, asked at the field

AdHoc can shrink an integer to the distance between its value and a base you declare — `[A]` when the values sit
near a floor, `[V]` near a ceiling, `[X]` around a centre, `[MinMax]` when the range is hard and narrow. What
decides this is the **physics of the field**, where its values actually sit, and a `.msg` file never says: it
gives a type and a name. How ROS itself stores the number decides nothing either, because AdHoc lays out its own
frame.

So the converter emits no attribute. It does not stay silent either: where the field name, its `#` comment or a
neighbouring constant gives a usable hint, and the field is an integer wider than one byte, it leaves the
question on the field with the candidate and the reason.

```csharp
// physics: a sequence_number has a floor at 0 and no ceiling, and stays small in practice → consider [A]
uint sequence_number;

// physics: an identifier or hash is spread over its whole type, which varint cannot shorten → leave fixed-width
ulong unique_id;

// physics: wall-clock seconds are ~1.7e9 today, past the 268 435 455 varint break-even → leave fixed-width (or use DateTime)
int sec;
```

43 such comments are generated for the shipped samples. The arithmetic behind them: varint pays while the typical
distance from the base stays under roughly two million, breaks even up to 268 435 455 and always loses beyond it,
so systematically large values (timestamps, scaled coordinates, ids, hashes) are worse off with it than without.
A span narrower than one byte is not a varint case at all — the generator rejects it and `[MinMax]` bit-packs it
instead. One-byte types (`bool`, `byte`, `sbyte`) and floats are never candidates, and are never commented on.

## Validation result

```
$ ./validate.sh AdHoc
ros2_interfaces                  OK
```

AdHocAgent (parse-only mode) accepts the descriptor without errors or warnings. Its FSM dump
(`AdHoc/ros2_interfaces.branches.txt`) shows the `Topics` state with all 203 message packs in both directions
(205 `.msg` files, less the two `builtin_interfaces` messages that became AdHoc's native time types), 34 RPC
actors (one per service) and 3 action actors.

## Limitations

- `.idl` files (OMG IDL, used by `test_msgs` in rcl_interfaces) are not read; only `.msg` / `.srv` / `.action`.
- `wstring` is treated as `string`; `char` as `byte` (both are what ROS 2 defines them to be on the wire).
- **Time resolution.** ROS timestamps and durations are nanosecond pairs; AdHoc normalises time to milliseconds,
  so sub-millisecond resolution is lost. A ROS `Duration` may be **negative**, AdHoc's `Duration` is non-negative
  elapsed time — if you need signed durations, replace the field with a signed integer of your own. Both facts are
  repeated as `// DROPPED:` comments on the generated `ROS_Duration` alias.
- **The generated `ROS_Duration` bounds are the widest the ROS type allows** (`int32` seconds ≈ 68 years at 1 ms).
  That is a safe default, not a good one: narrow `max` per use (a timeout under a minute wants `max => 60_000`)
  and AdHoc will shrink the field accordingly.
- Unbounded sequences and strings need a ceiling in AdHoc, set project-wide to 65 535 in `_DefaultMaxLengthOf`.
  That is a guess: lower it per field with `[D(+N)]` / `[D(N)]`, and raise it for payloads such as
  `sensor_msgs/Image.data`, which is a full frame.
- **No varint attributes are emitted**, only `// physics:` comments proposing them — see
  [below](#varint-is-your-decision-asked-at-the-field). Acting on those comments is the single most valuable
  hand-edit after conversion.
- Every type a bundle references must be in the bundle (the converter warns and leaves an `// unresolved` comment,
  and the agent then fails to compile the file). `fetch-samples.sh` therefore also pulls `unique_identifier_msgs`
  and installs `test_interface_files` under its ROS package name `test_msgs`.
- Wrapper messages such as `std_msgs/String`, `Byte`, `Char` keep their names; in Java or TypeScript output they
  shadow the language's own type names inside the generated package.
- The topology is a demo: ROS 2 nodes are peers, here one host calls services and sends goals, the other answers.
