package org.bndtools.builder;

import static org.assertj.core.api.Assertions.assertThat;
import static net.bytebuddy.jar.asm.Opcodes.ACONST_NULL;
import static net.bytebuddy.jar.asm.Opcodes.ARETURN;
import static net.bytebuddy.jar.asm.Opcodes.ASM9;
import static net.bytebuddy.jar.asm.Opcodes.ATHROW;
import static net.bytebuddy.jar.asm.Opcodes.GETFIELD;
import static net.bytebuddy.jar.asm.Opcodes.ICONST_0;
import static net.bytebuddy.jar.asm.Opcodes.ICONST_1;
import static net.bytebuddy.jar.asm.Opcodes.IFEQ;
import static net.bytebuddy.jar.asm.Opcodes.ILOAD;
import static net.bytebuddy.jar.asm.Opcodes.ISTORE;
import static net.bytebuddy.jar.asm.Opcodes.PUTFIELD;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;

class BndtoolsBuilderClasspathTest {
	private static final String				BUILDER	= "org/bndtools/builder/BndtoolsBuilder";
	private final Map<String, MethodCode>	methods	= new HashMap<>();
	private MethodCode						build;

	@BeforeEach
	void readBuilderCode() throws Exception {
		// Loading the class would install CnfWatcher in an Eclipse workspace.
		// Inspect the real compiled path without initializing the builder.
		try (InputStream input = getClass().getResourceAsStream("/" + BUILDER + ".class")) {
			assertThat(input).isNotNull();
			new ClassReader(input).accept(new ClassVisitor(ASM9) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
					String[] exceptions) {
					MethodCode code = new MethodCode();
					methods.put(name, code);
					return code;
				}
			}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		}
		build = methods.values()
			.stream()
			.filter(code -> code.instructions.contains("call " + BUILDER + ".requestClasspathContainerUpdate"))
			.findFirst()
			.orElseThrow();
	}

	@Test
	void changedClasspathReturnsBeforePackaging() {
		int guard = classpathGuard();
		int unchanged = build.target(guard);
		List<String> changed = build.instructions.subList(guard + 1, unchanged);

		assertThat(changed).containsSubsequence(
			"constant postponing build until Java compilation uses the updated classpath",
			"call org/bndtools/builder/BuildLogger.basic", "call " + BUILDER + ".postpone",
			"opcode " + ACONST_NULL, "opcode " + ARETURN)
			.doesNotContain("call aQute/bnd/build/Project.build");
		assertThat(build.instructions.indexOf("call aQute/bnd/build/Project.build")).isGreaterThan(unchanged);
	}

	@Test
	void unchangedClasspathContinuesWithoutPostponing() {
		int unchanged = build.target(classpathGuard());
		int packaging = build.instructions.indexOf("call aQute/bnd/build/Project.build");

		assertThat(build.instructions.subList(unchanged, packaging)).startsWith("opcode " + ICONST_1)
			.doesNotContain("call " + BUILDER + ".postpone")
			.contains("constant resuming postponed build");
	}

	@Test
	void postponementUsesSetupChangeAndActualClasspathUpdateResult() {
		int update = build.instructions.indexOf("call " + BUILDER + ".requestClasspathContainerUpdate");
		int guard = classpathGuard();
		int setupGuard = build.lastJumpBefore(update);
		assertThat(build.instructions.get(setupGuard)).startsWith("jump " + IFEQ + " ");
		assertThat(build.instructions.get(setupGuard - 1)).startsWith("variable " + ILOAD + " ");
		int unchanged = build.target(setupGuard);
		assertThat(build.instructions.get(unchanged)).isEqualTo("opcode " + ICONST_0);
		assertThat(build.instructions.get(update + 1)).startsWith("jump " + IFEQ + " ");
		assertThat(build.target(update + 1)).isEqualTo(unchanged);
		assertThat(build.instructions.get(update + 2)).isEqualTo("opcode " + ICONST_1);
		assertThat(build.instructions.get(unchanged + 1)).startsWith("variable " + ISTORE + " ");
		String resultVariable = build.instructions.get(unchanged + 1)
			.substring(("variable " + ISTORE + " ").length());
		assertThat(build.instructions.get(guard - 1)).isEqualTo("variable " + ILOAD + " " + resultVariable);
	}

	@Test
	void postponementRecordsFlagAndLastBuiltState() {
		assertThat(methods.get("postpone").instructions).containsSubsequence("opcode " + ICONST_1,
			"field " + PUTFIELD + " " + BUILDER + ".postponed", "call " + BUILDER + ".rememberLastBuiltState",
			"opcode " + ACONST_NULL, "opcode " + ARETURN);
	}

	@Test
	void resumeForcesBuildAndClearsFlagWithoutDiscardingBinChanges() {
		int log = build.instructions.indexOf("constant resuming postponed build");
		assertThat(log).isGreaterThan(1);
		assertThat(build.instructions.get(log - 4)).isEqualTo("field " + GETFIELD + " " + BUILDER + ".postponed");
		assertThat(build.instructions.get(log - 3)).startsWith("jump " + IFEQ + " ");
		int resumeEnd = build.target(log - 3);
		assertThat(build.instructions.subList(log, resumeEnd)).containsSubsequence(
			"call org/bndtools/builder/BuildLogger.basic", "opcode " + ICONST_1,
			"field " + PUTFIELD + " " + BUILDER + ".postponed");
		int reset = build.instructions.indexOf("field " + PUTFIELD + " " + BUILDER + ".postponed");
		assertThat(build.instructions.get(reset - 1)).isEqualTo("opcode " + ICONST_0);
		int force = build.target(classpathGuard()) + 1;
		assertThat(build.instructions.get(log + 3)).isEqualTo(build.instructions.get(force))
			.startsWith("variable " + ISTORE + " ");
		assertThat(build.instructions.subList(resumeEnd, build.instructions.size()))
			.containsSubsequence("call org/bndtools/builder/DeltaWrapper.hasProjectChanged",
				"call aQute/bnd/build/Project.build");
	}

	@Test
	void classpathUpdateErrorsAreNotConvertedToUnchangedClasspath() {
		assertThat(methods.get("requestClasspathContainerUpdate").handlers).isEmpty();
		assertThat(build.handlers).isEmpty();
		assertThat(methods.get("requestClasspathContainerUpdate").instructions)
			.contains("call org/bndtools/builder/classpath/BndContainerInitializer.requestClasspathContainerUpdate");
		assertThat(methods.get("build").instructions).containsSubsequence(
			"call org/eclipse/core/runtime/CoreException.<init>", "opcode " + ATHROW);
	}

	private int classpathGuard() {
		int update = build.instructions.indexOf("call " + BUILDER + ".requestClasspathContainerUpdate");
		int postpone = build.instructions.indexOf("call " + BUILDER + ".postpone");
		assertThat(postpone).isGreaterThan(update);
		int guard = build.lastJumpBefore(postpone);
		assertThat(build.instructions.get(guard)).startsWith("jump " + IFEQ + " ");
		return guard;
	}

	private static class MethodCode extends MethodVisitor {
		final List<String>			instructions	= new ArrayList<>();
		final Map<Label, Integer>	labels			= new HashMap<>();
		final Map<Integer, Label>	jumps			= new HashMap<>();
		final List<String>			handlers		= new ArrayList<>();

		MethodCode() {
			super(ASM9);
		}

		@Override
		public void visitInsn(int opcode) {
			instructions.add("opcode " + opcode);
		}

		@Override
		public void visitVarInsn(int opcode, int variable) {
			instructions.add("variable " + opcode + " " + variable);
		}

		@Override
		public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
			instructions.add("field " + opcode + " " + owner + "." + name);
		}

		@Override
		public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
			instructions.add("call " + owner + "." + name);
		}

		@Override
		public void visitLdcInsn(Object value) {
			instructions.add("constant " + value);
		}

		@Override
		public void visitJumpInsn(int opcode, Label label) {
			jumps.put(instructions.size(), label);
			instructions.add("jump " + opcode + " " + label);
		}

		@Override
		public void visitLabel(Label label) {
			labels.put(label, instructions.size());
		}

		@Override
		public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
			handlers.add(String.valueOf(type));
		}

		int target(int jump) {
			return labels.get(jumps.get(jump));
		}

		int lastJumpBefore(int instruction) {
			return jumps.keySet()
				.stream()
				.filter(index -> index < instruction)
				.mapToInt(Integer::intValue)
				.max()
				.orElseThrow();
		}
	}
}
