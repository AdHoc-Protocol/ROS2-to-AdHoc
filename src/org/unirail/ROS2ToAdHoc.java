package org.unirail;

import org.unirail.adhoc.AdHocWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.unirail.adhoc.AdHocWriter.*;

/**
 * ROS 2 interface files (.msg / .srv / .action) → AdHoc protocol description (.cs).
 *
 * <p>Usage: <code>java -cp out org.unirail.ROS2ToAdHoc &lt;folder with ROS packages&gt; [output folder]</code>
 *
 * <p>The input folder holds ROS packages (<code>&lt;pkg&gt;/msg/*.msg</code>, <code>&lt;pkg&gt;/srv/*.srv</code>,
 * <code>&lt;pkg&gt;/action/*.action</code>); a folder that itself contains msg/srv/action is treated as one package.
 * All packages are one logical bundle (they reference each other), so ONE descriptor named after the input folder
 * is written. Every package becomes a <code>struct &lt;pkg&gt;</code> container with <code>msg</code>,
 * <code>srv</code> and <code>action</code> sub-containers, so a type reference resolves as
 * <code>std_msgs.msg.Header</code> and the branch can select topic packs by name (<code>KeepName: @"\.msg\."</code>).
 *
 * <p>The emitter expresses ROS concepts in AdHoc's own vocabulary rather than transliterating them:
 * <code>builtin_interfaces/Time</code> becomes AdHoc's native {@code DateTime}, <code>builtin_interfaces/Duration</code>
 * a {@code : Duration} alias class, and the caps for what ROS leaves unbounded live in one
 * {@code _DefaultMaxLengthOf} enum — so a {@code [D(...)]} on a field always means the ROS file stated a real bound.
 * ROS 2 says nothing about the <i>distribution</i> of a numeric value, so no {@code [A]}/{@code [V]}/{@code [X]}
 * varint attribute is ever emitted.
 */
public class ROS2ToAdHoc {

	/** Cap for everything ROS leaves unbounded; emitted once as `enum _DefaultMaxLengthOf`, never per field. */
	static final int DEFAULT_MAX_LENGTH = 65_535;

	/** ROS messages that AdHoc models natively — mapped to a native type, never emitted as packs. */
	static final String ROS_TIME = "builtin_interfaces/Time";
	static final String ROS_DURATION = "builtin_interfaces/Duration";
	/** Name of the generated `: Duration` alias class that stands in for {@link #ROS_DURATION}. */
	static final String DURATION_ALIAS = "ROS_Duration";

	public static void main(String[] args) throws Exception {
		List<String> positional = new ArrayList<>(List.of(args));
		if (positional.isEmpty()) {
			System.out.println("Usage: java -cp out org.unirail.ROS2ToAdHoc <folder with ROS packages> [output folder]");
			return;
		}
		Path in = Paths.get(positional.get(0)).toAbsolutePath().normalize();
		Path out = 1 < positional.size() ? Paths.get(positional.get(1)) : Paths.get(System.getProperty("user.dir"), "AdHoc");
		if (!Files.isDirectory(in)) {
			System.err.println("Not a folder: " + in);
			System.exit(1);
		}
		Files.createDirectories(out);
		try {
			Bundle b = Bundle.load(in);
			Path dst = out.resolve(b.name + ".cs");
			Files.write(dst, new Emitter(b).emit().getBytes(StandardCharsets.UTF_8));
			int nativeTime = 0;
			for (Pkg p : b.packages.values()) for (Iface i : p.msg.values()) if (Emitter.isNativeTime(p.name + "/" + i.name)) nativeTime++;
			System.out.printf("%s -> %s  (%d packages, %d msg packs%s, %d srv, %d action)%n", in.getFileName(), dst, b.packages.size(),
					b.count("msg") - nativeTime, 0 < nativeTime ? " + " + nativeTime + " mapped to native AdHoc time types" : "", b.count("srv"), b.count("action"));
			if (0 < b.warnings) System.err.println(b.warnings + " warning(s), see above");
		} catch (Exception e) {
			System.err.println("FAILED " + in + ": " + e);
			e.printStackTrace();
			System.exit(2);
		}
	}

	// ═══════════════════════════════════════════ model ═══════════════════════════════════════════

	/** A parsed type spec such as {@code string<=10[<=5]} or {@code geometry_msgs/Point[]}. */
	static final class Type {
		String base;         // primitive name, or "pkg/Name", or "Name"
		Integer strMax;      // string<=N
		int arrayKind;       // 0 scalar, 1 fixed [N], 2 bounded [<=N], 3 unbounded []
		int arrayLen;
		String raw;

		static final Pattern P = Pattern.compile("^([A-Za-z0-9_/]+)(?:<=(\\d+))?(?:\\[(<=)?(\\d*)\\])?$");

		static Type parse(String spec) {
			Matcher m = P.matcher(spec);
			if (!m.matches()) throw new IllegalArgumentException("cannot parse type `" + spec + "`");
			Type t = new Type();
			t.raw = spec;
			t.base = m.group(1);
			if (m.group(2) != null) t.strMax = Integer.parseInt(m.group(2));
			if (m.group(4) != null) { // brackets present
				if (m.group(3) != null) { t.arrayKind = 2; t.arrayLen = Integer.parseInt(m.group(4)); }
				else if (!m.group(4).isEmpty()) { t.arrayKind = 1; t.arrayLen = Integer.parseInt(m.group(4)); }
				else t.arrayKind = 3;
			}
			return t;
		}
	}

	static final class Member {
		boolean constant;
		Type type;
		String name, value; // constant value or field default (raw source text), may be null
		String doc = "";
	}

	/** One message body: a .msg, or one section of a .srv / .action. */
	static final class Section {
		String doc = "";
		final List<Member> members = new ArrayList<>();
	}

	static final class Iface {
		String pkg, kind, name;        // kind: msg | srv | action
		Path file;
		final List<Section> sections = new ArrayList<>();
	}

	static final class Pkg {
		String name;
		final Map<String, Iface> msg = new TreeMap<>(), srv = new TreeMap<>(), action = new TreeMap<>();
	}

	static final class Bundle {
		String name;
		final Map<String, Pkg> packages = new TreeMap<>();
		int warnings;

		int count(String kind) {
			int n = 0;
			for (Pkg p : packages.values()) n += kind.equals("msg") ? p.msg.size() : kind.equals("srv") ? p.srv.size() : p.action.size();
			return n;
		}

		static Bundle load(Path root) throws IOException {
			Bundle b = new Bundle();
			b.name = ident(root.getFileName().toString());
			List<Path> pkgDirs = new ArrayList<>();
			if (Files.isDirectory(root.resolve("msg")) || Files.isDirectory(root.resolve("srv")) || Files.isDirectory(root.resolve("action"))) pkgDirs.add(root);
			else try (Stream<Path> s = Files.list(root)) { s.filter(Files::isDirectory).sorted().forEach(pkgDirs::add); }

			for (Path dir : pkgDirs) {
				Pkg p = new Pkg();
				p.name = dir.getFileName().toString();
				for (String kind : new String[]{"msg", "srv", "action"}) {
					Path kd = dir.resolve(kind);
					if (!Files.isDirectory(kd)) continue;
					List<Path> files = new ArrayList<>();
					try (Stream<Path> s = Files.list(kd)) { s.filter(f -> f.toString().endsWith("." + kind)).sorted().forEach(files::add); }
					for (Path f : files) {
						Iface i = parseFile(f, p.name, kind);
						int expected = kind.equals("msg") ? 1 : kind.equals("srv") ? 2 : 3;
						if (i.sections.size() != expected) throw new IllegalArgumentException(f + ": expected " + expected + " section(s) separated by ---, found " + i.sections.size());
						(kind.equals("msg") ? p.msg : kind.equals("srv") ? p.srv : p.action).put(i.name, i);
					}
				}
				if (p.msg.isEmpty() && p.srv.isEmpty() && p.action.isEmpty()) continue;
				b.packages.put(p.name, p);
			}
			if (b.packages.isEmpty()) throw new IllegalArgumentException("no ROS packages (folders with msg/ srv/ action/) found in " + root);
			return b;
		}
	}

	// ═══════════════════════════════════════════ parser ═══════════════════════════════════════════

	static final Pattern CONSTANT = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*)$");

	static Iface parseFile(Path file, String pkg, String kind) throws IOException {
		Iface i = new Iface();
		i.pkg = pkg;
		i.kind = kind;
		i.file = file;
		String fn = file.getFileName().toString();
		i.name = fn.substring(0, fn.length() - kind.length() - 1);

		Section s = new Section();
		i.sections.add(s);
		List<String> pending = new ArrayList<>();
		int lineNo = 0;
		for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			lineNo++;
			String line = raw;
			if (line.trim().equals("---")) {
				flushDoc(s, pending);
				s = new Section();
				i.sections.add(s);
				continue;
			}
			// split off the comment (a '#' outside quotes)
			String comment = null;
			int hash = commentStart(line);
			if (0 <= hash) {
				comment = line.substring(hash + 1).trim();
				line = line.substring(0, hash);
			}
			line = line.trim();
			if (line.isEmpty()) {
				if (comment != null) pending.add(comment);
				else if (s.doc.isEmpty() && s.members.isEmpty() && !pending.isEmpty()) { // top-of-file block → message doc
					s.doc = String.join("\n", pending);
					pending.clear();
				}
				continue;
			}
			int sp = firstSpace(line);
			if (sp < 0) throw new IllegalArgumentException(file + ":" + lineNo + ": `" + raw + "` has no field name");
			Member m = new Member();
			try { m.type = Type.parse(line.substring(0, sp)); } catch (IllegalArgumentException e) { throw new IllegalArgumentException(file + ":" + lineNo + ": " + e.getMessage()); }
			String rest = line.substring(sp).trim();
			Matcher c = CONSTANT.matcher(rest);
			if (c.matches()) {
				m.constant = true;
				m.name = c.group(1);
				m.value = c.group(2).trim();
			} else {
				int sp2 = firstSpace(rest);
				m.name = sp2 < 0 ? rest : rest.substring(0, sp2);
				if (0 <= sp2) m.value = rest.substring(sp2).trim(); // default value
			}
			StringBuilder d = new StringBuilder(String.join("\n", pending));
			pending.clear();
			if (comment != null && !comment.isEmpty()) { if (0 < d.length()) d.append('\n'); d.append(comment); }
			m.doc = d.toString();
			s.members.add(m);
		}
		flushDoc(s, pending);
		return i;
	}

	static void flushDoc(Section s, List<String> pending) {
		if (!pending.isEmpty() && s.doc.isEmpty() && s.members.isEmpty()) s.doc = String.join("\n", pending);
		pending.clear();
	}

	static int commentStart(String line) {
		char quote = 0;
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (quote != 0) { if (c == '\\') i++; else if (c == quote) quote = 0; }
			else if (c == '"' || c == '\'') quote = c;
			else if (c == '#') return i;
		}
		return -1;
	}

	static int firstSpace(String s) {
		for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return i;
		return -1;
	}

	// ═══════════════════════════════════════════ emitter ═══════════════════════════════════════════

	static final class Emitter {
		final Bundle b;
		final StringBuilder sb = new StringBuilder(1 << 20);
		final Map<String, Integer> dashboard = new TreeMap<>();
		final Map<String, Iface> knownMsgs = new LinkedHashMap<>(); // "pkg/Name" → message
		boolean usesDuration; // set when a field mapped to the `: Duration` alias, which is then declared

		Emitter(Bundle b) {
			this.b = b;
			for (Pkg p : b.packages.values()) for (Iface i : p.msg.values()) knownMsgs.put(p.name + "/" + i.name, i);
		}

		/** True for the two ROS messages AdHoc models natively; they are mapped, not emitted. */
		static boolean isNativeTime(String ref) { return ROS_TIME.equals(ref) || ROS_DURATION.equals(ref); }

		/** True when the message declares no instance fields (constants only, or nothing at all). */
		static boolean isEmpty(Iface i) {
			for (Member m : i.sections.get(0).members) if (!m.constant) return false;
			return true;
		}

		String emit() {
			StringBuilder body = new StringBuilder();
			for (Pkg p : b.packages.values()) packageContainer(body, p);
			// the dashboard needs every pack name, so the body is rendered first
			fileHeader(sb, "ROS2ToAdHoc from ROS 2 interface files (.msg / .srv / .action)", String.join(", ", b.packages.keySet()),
					"builtin_interfaces/Time and /Duration are mapped to AdHoc's native time types, not to packs",
					"ROS states nothing about the distribution of a number, so no [A]/[V]/[X] varint attribute is emitted");
			sb.append("namespace org.ros2 {\n");
			dashboard(sb, I1, dashboard);
			sb.append(I1).append("public interface ").append(b.name).append(" {\n");
			defaults();
			sb.append(body);
			if (usesDuration) durationAlias();
			topology();
			attributes();
			sb.append(I1).append("}\n}\n");
			return sb.toString();
		}

		/** One cap for everything ROS leaves unbounded, so a per-field [D] always means a bound from the source. */
		void defaults() {
			sb.append('\n').append(I2).append("// ROS 2 sequences and strings are unbounded unless the .msg says otherwise; AdHoc needs a ceiling.\n");
			sb.append(I2).append("// It is stated once here instead of on every field, so a [D(...)] below always comes from the ROS file.\n");
			sb.append(I2).append("// Lower these per field with [D(+N)] / [D(N)] where the real payload is known.\n");
			sb.append(I2).append("enum _DefaultMaxLengthOf {\n");
			for (String k : new String[]{"Arrays", "Maps", "Sets", "Strings"})
				sb.append(I3).append(k).append(pad(7 - k.length())).append(" = ").append(grouped(DEFAULT_MAX_LENGTH)).append(",\n");
			sb.append(I2).append("}\n");
		}

		/** 65535 → "65_535": C# digit separators, locale-independent (String.format's grouping is not). */
		static String grouped(long v) {
			StringBuilder s = new StringBuilder(Long.toString(v));
			for (int i = s.length() - 3; 0 < i; i -= 3) s.insert(i, '_');
			return s.toString();
		}

		/** The `: Duration` alias standing in for builtin_interfaces/Duration. */
		void durationAlias() {
			sb.append('\n').append(I2).append("// builtin_interfaces/Duration is an elapsed time, which AdHoc models natively: a `: Duration` alias\n");
			sb.append(I2).append("// is bit-sized from the range below instead of shipping the two raw ROS fields (int32 sec + uint32 nanosec).\n");
			sb.append(I2).append("// DROPPED: ROS allows a NEGATIVE duration (sec < 0); AdHoc's Duration is non-negative elapsed time.\n");
			sb.append(I2).append("// DROPPED: nanosecond resolution - AdHoc normalises time to milliseconds and clamps precision at 1 ms.\n");
			sb.append(I2).append("// Refine per use: a timeout that is always under a minute wants max => 60_000, not the ROS int32 ceiling.\n");
			sb.append(I2).append("public class ").append(DURATION_ALIAS).append(" : Duration {\n");
			sb.append(I3).append("public long     max       => 2_147_483_647_000; // int32.MaxValue seconds, in 1 ms steps\n");
			sb.append(I3).append("public TimeSpan precision => TimeSpan.FromMilliseconds(1);\n");
			sb.append(I2).append("}\n");
		}

		void packageContainer(StringBuilder out, Pkg p) {
			// Messages AdHoc models natively are replaced by that native type, so they are not emitted at all.
			List<Iface> msgs = new ArrayList<>(), native_ = new ArrayList<>();
			for (Iface i : p.msg.values()) (isNativeTime(p.name + "/" + i.name) ? native_ : msgs).add(i);

			out.append('\n').append(I2).append("// ═════════════════════════ package ").append(p.name).append(" ═════════════════════════\n");
			for (Iface i : native_) {
				String ref = p.name + "/" + i.name;
				out.append(I2).append("// DROPPED as a pack: ").append(ref).append(" is expressed in AdHoc's own vocabulary as ")
						.append(ROS_TIME.equals(ref) ? "the native `DateTime`" : "the `" + DURATION_ALIAS + " : Duration` alias").append(",\n");
				out.append(I2).append("// so its two raw fields (int32 sec + uint32 nanosec) never reach the wire and every field of that type maps straight onto it.\n");
			}
			if (msgs.isEmpty() && p.srv.isEmpty() && p.action.isEmpty()) {
				out.append(I2).append("// Nothing else is left in this package, so no container is generated for it.\n");
				return;
			}
			out.append(I2).append("public struct ").append(ident(p.name)).append(" {\n");
			if (!msgs.isEmpty()) {
				out.append(I3).append("public struct msg {\n");
				for (Iface i : msgs) pack(out, I4, i, ident(i.name), i.sections.get(0), i.pkg, "msg");
				out.append(I3).append("}\n");
			}
			if (!p.srv.isEmpty()) {
				out.append(I3).append("public struct srv {\n");
				for (Iface i : p.srv.values()) {
					pack(out, I4, i, ident(i.name + "_Request"), i.sections.get(0), i.pkg, "srv");
					pack(out, I4, i, ident(i.name + "_Response"), i.sections.get(1), i.pkg, "srv");
				}
				out.append(I3).append("}\n");
			}
			if (!p.action.isEmpty()) {
				out.append(I3).append("public struct action {\n");
				for (Iface i : p.action.values()) {
					pack(out, I4, i, ident(i.name + "_Goal"), i.sections.get(0), i.pkg, "action");
					pack(out, I4, i, ident(i.name + "_Result"), i.sections.get(1), i.pkg, "action");
					pack(out, I4, i, ident(i.name + "_Feedback"), i.sections.get(2), i.pkg, "action");
				}
				out.append(I3).append("}\n");
			}
			out.append(I2).append("}\n");
		}

		void pack(StringBuilder out, String indent, Iface i, String cls, Section s, String pkg, String kind) {
			dashboard.put(ident(pkg) + "." + kind + "." + cls, null);
			out.append('\n');
			String d = s.doc;
			if (!i.file.getFileName().toString().endsWith(".msg")) d = (d.isEmpty() ? "" : d + "\n") + "(" + i.file.getFileName() + ")";
			doc(out, indent, d);
			out.append(indent).append("public class ").append(cls).append(" {\n");
			Set<String> taken = new HashSet<>();
			taken.add(cls); // a pack may not contain a member with its own name
			for (Member m : s.members) {
				doc(out, indent + I1, m.doc);
				out.append(indent).append(I1).append(m.constant ? constant(m, taken) : field(m, pkg, taken, i.file)).append('\n');
			}
			out.append(indent).append("}\n");
		}

		String constant(Member m, Set<String> taken) {
			String cs = primitive(m.type.base);
			if (cs == null || m.type.arrayKind != 0) throw new IllegalArgumentException("constant " + m.name + " must be a primitive scalar, got " + m.type.raw);
			String name = unique(m.name, taken);
			return "const " + cs + " " + name + " = " + literal(cs, m.value) + ";";
		}

		String field(Member m, String pkg, Set<String> taken, Path file) {
			Type t = m.type;
			List<String> attrs = new ArrayList<>();
			List<String> dims = new ArrayList<>();
			String cs = primitive(t.base);
			boolean isString = cs != null && cs.equals("string");
			String comment = "";
			if (cs == null) {
				String ref = t.base.contains("/") ? t.base : pkg + "/" + t.base;
				String[] pn = ref.split("/");
				if (ROS_TIME.equals(ref)) {
					// A wall-clock timestamp: AdHoc has a native type for it, so the two raw ROS fields never
					// reach the wire. Nanosecond resolution is lost (AdHoc normalises time to milliseconds).
					cs = "DateTime";
					comment = " // was " + ref + " (int32 sec + uint32 nanosec); ns resolution dropped";
				} else if (ROS_DURATION.equals(ref)) {
					cs = DURATION_ALIAS;
					usesDuration = true;
					comment = " // was " + ref + "; see the " + DURATION_ALIAS + " alias";
				} else {
					cs = ident(pn[0]) + ".msg." + ident(pn[1]);
					Iface target = knownMsgs.get(ref);
					if (target == null) {
						b.warnings++;
						System.err.println("WARNING " + file.getFileName() + ": field " + m.name + " references " + ref + " which is not in the bundle");
						comment = " // unresolved: " + ref;
					} else if (isEmpty(target)) {
						// An empty pack used as a field carries nothing but its presence; AdHoc represents it as a bool
						// (and would warn while doing so), so the converter says it explicitly.
						cs = "bool";
						comment = " // " + ref + " has no fields: presence flag";
					}
				}
			}
			// [D] is emitted ONLY where the ROS file states a real bound; everything else takes the
			// project-wide ceiling from `_DefaultMaxLengthOf`.
			if (isString && t.strMax != null) dims.add("+" + t.strMax);
			switch (t.arrayKind) {
				case 1: dims.add(Integer.toString(t.arrayLen)); cs += "[]"; break;   // T[N]    constant length
				case 2: dims.add(Integer.toString(t.arrayLen)); cs += "[,,]"; break; // T[<=N]  bounded list
				case 3: cs += "[,,]"; break;                                         // T[]     unbounded: _DefaultMaxLengthOf
				default: break;
			}
			if (!dims.isEmpty()) attrs.add("D(" + String.join(", ", dims) + ")");
			if (m.value != null) attrs.add("Default(" + str(m.value) + ")");
			String name = unique(m.name, taken);
			return (attrs.isEmpty() ? "" : "[" + String.join(", ", attrs) + "] ") + cs + " " + name + ";" + comment;
		}

		/** A C# literal for a constant of the given C# type from the ROS source text. */
		static String literal(String cs, String v) {
			switch (cs) {
				case "bool": return v.equalsIgnoreCase("true") || v.equals("1") ? "true" : "false";
				case "string":
					if (2 <= v.length() && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) v = v.substring(1, v.length() - 1);
					return str(v);
				case "float": return Double.toString(Double.parseDouble(v)) + "f";
				case "double": return Double.toString(Double.parseDouble(v));
				default: return Long.toString(parseInt(v));
			}
		}

		static long parseInt(String v) {
			String s = v.trim().replace("_", "");
			boolean neg = s.startsWith("-");
			if (neg || s.startsWith("+")) s = s.substring(1);
			long r;
			if (s.startsWith("0x") || s.startsWith("0X")) r = Long.parseUnsignedLong(s.substring(2), 16);
			else if (s.startsWith("0b") || s.startsWith("0B")) r = Long.parseUnsignedLong(s.substring(2), 2);
			else if (s.startsWith("0o") || s.startsWith("0O")) r = Long.parseUnsignedLong(s.substring(2), 8);
			else r = Long.parseUnsignedLong(s);
			return neg ? -r : r;
		}

		// ───────────────────────────── topology ─────────────────────────────

		void topology() {
			sb.append('\n').append(I2).append("// ═════════════════════════ topology ═════════════════════════\n\n");
			sb.append(I2).append("// ROS 2 has no fixed topology: any node may publish any topic, call any service or act on any action.\n");
			sb.append(I2).append("// The demo pairs a Client (calls services, sends action goals) with a Server (answers them);\n");
			sb.append(I2).append("// topics flow in both directions.\n\n");
			host(sb, I2, "Client", null);
			host(sb, I2, "Server", null);

			sb.append(I2).append("interface Communication : Connects<Client, Server> {\n");
			sb.append(I3).append("// Topics: every *.msg pack of every package, selected by the `.msg.` container in its full name;\n");
			sb.append(I3).append("// either side may publish, the FSM never transitions.\n");
			sb.append(I3).append("[_____lr_____<@").append(b.name).append(">(KeepName: @\"\\.msg\\.\")]\n");
			sb.append(I3).append("struct Topics { }\n");

			boolean anySrv = false;
			for (Pkg p : b.packages.values()) if (!p.srv.isEmpty()) anySrv = true;
			if (anySrv) {
				sb.append('\n').append(I3).append("// Services: request from the Client, response from the Server (RPC shorthand, one transient actor per call).\n");
				for (Pkg p : b.packages.values())
					for (Iface i : p.srv.values()) {
						String base = ident(p.name) + ".srv." + ident(i.name);
						sb.append(I3).append("(L____________, ").append(base).append("_Response) ").append(ident(p.name + "_" + i.name)).append("(").append(base).append("_Request req);\n");
					}
			}

			boolean cancel = b.packages.containsKey("action_msgs") && b.packages.get("action_msgs").srv.containsKey("CancelGoal");
			for (Pkg p : b.packages.values())
				for (Iface i : p.action.values()) {
					String base = ident(p.name) + ".action." + ident(i.name);
					sb.append('\n').append(I3).append("// Action ").append(p.name).append("/").append(i.name).append(": goal → feedback stream → result. Several goals may be in flight at once.\n");
					sb.append(I3).append("interface ").append(ident(p.name + "_" + i.name)).append(" : Actor {\n");
					sb.append(I4).append("int MaxActiveInstances => UNLIMITED;\n\n");
					sb.append(I4).append("[L____________<Executing, ").append(base).append("_Goal>]\n");
					sb.append(I4).append("struct SendGoal { }\n\n");
					sb.append(I4).append("[____________r<").append(base).append("_Feedback>]\n");
					if (cancel) sb.append(I4).append("[l____________<action_msgs.srv.CancelGoal_Request>]\n");
					sb.append(I4).append("[____________R<End, ").append(base).append("_Result>]\n");
					sb.append(I4).append("struct Executing { }\n");
					sb.append(I3).append("}\n");
				}
			sb.append(I2).append("}\n");
		}

		void attributes() {
			sb.append('\n').append(I2).append("// ═════════════════════════ ROS 2 metadata attributes ═════════════════════════\n\n");
			sb.append(I2).append("// Custom attribute: carried into the generated code as a constant attached to the field.\n");
			sb.append(I2).append("// Declared inside the project interface (the agent expects every class in a project);\n");
			sb.append(I2).append("// the Topics branch selects packs by `.msg.` so this class is never collected.\n");
			attribute(sb, I2, "Default", "Field default value, as written in the ROS interface file.", "string value");
		}
	}

	// ═══════════════════════════════════════════ type mapping ═══════════════════════════════════════════

	/** ROS 2 primitive → C#; null for a non-primitive (message) type. */
	static String primitive(String base) {
		switch (base) {
			case "bool": return "bool";
			case "byte":
			case "uint8":
			case "char": return "byte";   // char is an unsigned 8-bit value in ROS 2
			case "int8": return "sbyte";
			case "int16": return "short";
			case "uint16": return "ushort";
			case "int32": return "int";
			case "uint32": return "uint";
			case "int64": return "long";
			case "uint64": return "ulong";
			case "float32": return "float";
			case "float64": return "double";
			case "string":
			case "wstring": return "string";
			default: return null;
		}
	}
}
