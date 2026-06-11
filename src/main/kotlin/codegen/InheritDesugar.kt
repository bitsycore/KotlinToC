package com.bitsycore.ktc.codegen

import com.bitsycore.ktc.ast.*

/* Class-inheritance desugaring — runs on the parsed ASTs, before any codegen.

KTC compiles `open` / `abstract` / `sealed` class hierarchies down to the
existing interface machinery (fat tagged-union values, per-class vtables,
whole-program typeId matching), plus data/code inheritance by copying:

  open class Animal(val name: String) {        interface Animal { val name: String; fun speak(): String }
      open fun speak(): String = "..."    →    class Animal$Impl(val name: String) : Animal { override fun speak() ... }
  }
  class Dog(name: String) : Animal(name) {     class Dog(name: String) : Animal {
      override fun speak() = "Woof"        →       val name: String = name      // inherited field, super-arg init
  }                                               override fun speak() = "Woof"
                                               }

- An extendable class P (open/abstract/sealed) becomes interface P (its method
  signatures + stored props) — every type reference to P then lowers through
  the interface machinery untouched (is/as/when/catch/dispatch).
- An `open` class additionally gets a hidden concrete `P$Impl`; constructor
  calls `P(args)` are rewritten to `P$Impl(args)`. abstract/sealed classes are
  not instantiable (refused here).
- A child `class C : P(args)` implements interface P and is augmented with:
  P's stored ctor props as body fields initialized from the super-call args
  (or P's parameter defaults), P's body props, P's concrete method bodies
  (marked override), and P's init blocks. Method copying is monomorphization
  in the KTC spirit — and it makes virtual dispatch correct even for template
  methods: a copied parent body resolves calls against the child.
- Chains (A : B : C) flatten transitively: children copy from the already
  AUGMENTED parent.

V1 restrictions (clear errors below): parent ctor params must be val/var, no
generic parents, no `super.method()` calls, positional super-args only. */

// ==================
// MARK: Entry point
// ==================

object InheritDesugar {

	/* Apply the desugaring to all files of the program. Returns the rewritten
	file list (same order); files without class hierarchies pass through as-is. */
	fun apply(inFiles: List<KtFile>): List<KtFile> {
		// 1. Index all top-level classes by name (documentation-only files excluded).
		val vClasses = mutableMapOf<String, ClassDecl>()
		for (vFile in inFiles) {
			if (vFile.documentationOnly) continue
			for (vDecl in vFile.decls) if (vDecl is ClassDecl) vClasses[vDecl.name] = vDecl
		}
		val vExtendable = vClasses.filterValues { it.isOpen || it.isAbstract || it.isSealed }
		validate(vClasses, vExtendable)
		// Nothing left to do for programs without class hierarchies.
		val vHasChildren = vClasses.values.any { it.superClassName != null }
		if (vExtendable.isEmpty() && !vHasChildren) return inFiles

		// 2. Augment classes parent-first (children copy from the augmented parent).
		val vAugmented = mutableMapOf<String, ClassDecl>()
		fun augmentedOf(inName: String): ClassDecl {
			vAugmented[inName]?.let { return it }
			val vDecl = vClasses[inName] ?: error("Unknown class '$inName' in inheritance chain")
			val vResult = if (vDecl.superClassName == null) vDecl
				else augmentChild(vDecl, augmentedOf(vDecl.superClassName))
			vAugmented[inName] = vResult
			return vResult
		}
		for (vName in vClasses.keys) augmentedOf(vName)

		// 3. Rebuild each file: extendable classes → interface (+ $Impl); children → augmented.
		val vOpenImpls = vExtendable.filterValues { it.isOpen }.keys
		val vResultFiles = inFiles.map { vFile ->
			if (vFile.documentationOnly) return@map vFile
			val vNewDecls = vFile.decls.flatMap { vDecl ->
				if (vDecl !is ClassDecl) return@flatMap listOf(vDecl)
				val vAug = vAugmented[vDecl.name] ?: vDecl
				when {
					vDecl.name in vExtendable && vDecl.isOpen -> listOf(toInterface(vAug), toImplClass(vAug))
					vDecl.name in vExtendable                 -> listOf(toInterface(vAug))
					else                                       -> listOf(vAug)
				}
			}
			vFile.copy(decls = vNewDecls)
		}

		// 4. Rewrite constructor calls of open classes (P(...) → P$Impl(...)) and
		//    refuse instantiation of abstract/sealed classes — across every expression.
		val vAbstractNames = vExtendable.filterValues { !it.isOpen }.keys
		return vResultFiles.map { vFile ->
			if (vFile.documentationOnly) vFile
			else vFile.copy(decls = vFile.decls.map { rewriteDecl(it, vOpenImpls, vAbstractNames) })
		}
	}

	// ==================
	// MARK: Validation
	// ==================

	private fun validate(inClasses: Map<String, ClassDecl>, inExtendable: Map<String, ClassDecl>) {
		for ((vName, vDecl) in inExtendable) {
			if (vDecl.typeParams.isNotEmpty())
				error("Class '$vName': generic classes cannot be open/abstract/sealed yet — use a sealed/generic interface instead")
			if (vDecl.isData)
				error("Class '$vName': a data class cannot be open/abstract/sealed (Kotlin rule)")
			if (vDecl.isValue)
				error("Class '$vName': a value class cannot be open/abstract/sealed")
			for (vM in vDecl.members.filterIsInstance<FunDecl>()) {
				if (vM.isAbstract && vM.body != null)
					error("Class '$vName': abstract function '${vM.name}' must not have a body")
				if (vM.isAbstract && !vDecl.isAbstract && !vDecl.isSealed)
					error("Class '$vName': abstract function '${vM.name}' requires an abstract (or sealed) class")
			}
		}
		for ((vName, vDecl) in inClasses) {
			// Non-extendable classes must not declare abstract members or be extended.
			if (vName !in inExtendable) {
				vDecl.members.filterIsInstance<FunDecl>().firstOrNull { it.isAbstract }?.let {
					error("Class '$vName': abstract function '${it.name}' requires an abstract class")
				}
			}
			// `super.` only makes sense with a class parent.
			if (vDecl.superClassName == null || inClasses[vDecl.superClassName] == null) {
				vDecl.members.filterIsInstance<FunDecl>().firstOrNull { bodyCallsSuper(it.body) }?.let {
					error("Class '$vName': method '${it.name}' uses 'super.' but the class has no parent class")
				}
			}
			val vParentName = vDecl.superClassName ?: continue
			val vParent = inClasses[vParentName]
				?: continue  // parens on an interface/unknown name — left for codegen to diagnose
			if (vParentName !in inExtendable)
				error("Class '$vName' extends '$vParentName', which is final — mark it 'open', 'abstract' or 'sealed'")
			if (vParent.typeParams.isNotEmpty())
				error("Class '$vName': extending the generic class '$vParentName' is not supported yet")
			// Cycle guard: walk the parent chain.
			var vCur: String? = vParentName
			val vSeen = mutableSetOf(vName)
			while (vCur != null) {
				if (!vSeen.add(vCur)) error("Inheritance cycle through class '$vCur'")
				vCur = inClasses[vCur]?.superClassName
			}
		}
	}

	// ==================
	// MARK: Child augmentation
	// ==================

	/* Build the augmented form of [inChild]: parent stored fields injected (with
	super-arg / default initializers), parent body props, parent concrete methods
	(unless overridden) and parent init blocks copied in. [inParent] is already
	augmented, so chains flatten transitively. */
	private fun augmentChild(inChild: ClassDecl, inParent: ClassDecl): ClassDecl {
		val vChildName = inChild.name
		val vArgs = inChild.superClassArgs ?: emptyList()
		if (vArgs.any { it.name != null })
			error("Class '$vChildName': named arguments in the super-constructor call are not supported yet — use positional arguments")
		if (vArgs.size > inParent.ctorParams.size)
			error("Class '$vChildName': too many super-constructor arguments for '${inParent.name}' " +
				"(${vArgs.size} given, ${inParent.ctorParams.size} expected)")

		// Collision checks: a child must not redeclare an inherited stored property.
		val vParentStored = inParent.ctorParams.filter { it.isVal || it.isVar }.map { it.name } +
			inParent.members.filterIsInstance<PropDecl>().map { it.name }
		for (vCp in inChild.ctorParams) {
			if ((vCp.isVal || vCp.isVar) && vCp.name in vParentStored)
				error("Class '$vChildName': '${vCp.name}' is already a stored property of '${inParent.name}' — " +
					"pass it through the super-constructor instead of redeclaring it (drop the val/var)")
		}
		for (vP in inChild.members.filterIsInstance<PropDecl>()) {
			if (vP.name in vParentStored)
				error("Class '$vChildName': property '${vP.name}' is already declared in '${inParent.name}'")
		}

		// Each parent ctor param maps to the child's super-call argument (positionally),
		// falling back to the parameter's default value.
		val vParamValue = inParent.ctorParams.mapIndexed { vI, vCp ->
			vCp.name to (vArgs.getOrNull(vI)?.expr ?: vCp.default
				?: error("Class '$vChildName': missing super-constructor argument for '${inParent.name}.${vCp.name}' (no default value)"))
		}.toMap()

		// val/var ctor params become stored fields initialized from the super-args.
		val vInjectedProps = inParent.ctorParams.filter { it.isVal || it.isVar }.map { vCp ->
			PropDecl(vCp.name, vCp.type, vParamValue[vCp.name], mutable = vCp.isVar, isPrivate = vCp.isPrivate)
		}
		// Plain (forwarding) parent ctor params have no storage — references to them
		// inside the parent's body-prop initializers and init blocks are substituted
		// with the super-call argument expression. (An argument referenced more than
		// once is evaluated once per reference — keep super-args simple.)
		val vForwardSubst = inParent.ctorParams.filter { !it.isVal && !it.isVar }
			.associate { it.name to vParamValue[it.name]!! }
		val vInjectedBodyProps = inParent.members.filterIsInstance<PropDecl>().map { vP ->
			vP.copy(init = vP.init?.let { substituteNames(it, vForwardSubst) })
		}

		// Parent concrete methods not overridden by the child, copied override-marked.
		val vChildMethods = inChild.members.filterIsInstance<FunDecl>()
		fun overriddenBy(inM: FunDecl): FunDecl? =
			vChildMethods.find { it.name == inM.name && it.params.size == inM.params.size }
		val vInheritedMethods = mutableListOf<FunDecl>()
		for (vM in inParent.members.filterIsInstance<FunDecl>()) {
			val vOverride = overriddenBy(vM)
			if (vOverride != null) {
				// An override is itself open for further overriding (Kotlin semantics).
				if (!vM.isOpen && !vM.isAbstract && !vM.isOverride)
					error("Class '$vChildName': '${vM.name}' in '${inParent.name}' is final — mark it 'open' (or 'abstract') to override it")
				if (!vOverride.isOverride)
					error("Class '$vChildName': '${vOverride.name}' overrides '${inParent.name}.${vM.name}' and must be marked 'override'")
				continue
			}
			if (vM.body == null) continue        // abstract — enforced via the interface (E100)
			vInheritedMethods += vM.copy(isOverride = !vM.isPrivate, isOpen = vM.isOpen)
		}

		// super.method() / super.prop support: each super-called parent method gets a
		// private, level-qualified copy (name$super$Parent — multi-level chains stay
		// distinct), the call rewrites to it, and super.prop collapses to this.prop
		// (fields are merged; shadowing is refused above).
		val vSuperCalled = mutableSetOf<String>()
		for (vM in vChildMethods) walkBlockOrNull(vM.body) { vE ->
			if (vE is CallExpr && vE.callee is DotExpr && ((vE.callee as DotExpr).obj as? NameExpr)?.name == "super")
				vSuperCalled += (vE.callee as DotExpr).name
		}
		for (vB in inChild.initBlocks) walkBlock(vB) { vE ->
			if (vE is CallExpr && vE.callee is DotExpr && ((vE.callee as DotExpr).obj as? NameExpr)?.name == "super")
				vSuperCalled += (vE.callee as DotExpr).name
		}
		val vSuperCopies = vSuperCalled.map { vN ->
			val vPm = inParent.members.filterIsInstance<FunDecl>().find { it.name == vN && it.body != null }
				?: error("Class '$vChildName': super.$vN — '${inParent.name}' has no concrete method '$vN'")
			vPm.copy(name = "$vN\$super\$${inParent.name}", isPrivate = true, isOverride = false, isOpen = false)
		}
		fun rewriteSuperInDecl(inD: Decl): Decl = when (inD) {
			is FunDecl  -> inD.copy(body = inD.body?.let { rewriteSuperBlock(it, inParent.name) })
			is PropDecl -> inD.copy(init = inD.init?.let { rewriteSuperExpr(it, inParent.name) })
			else        -> inD
		}
		val vOwnMembers = inChild.members.map { rewriteSuperInDecl(it) }
		val vOwnInits   = inChild.initBlocks.map { rewriteSuperBlock(it, inParent.name) }

		val vParentInits = inParent.initBlocks.map { vB ->
			Block(vB.stmts.map { substituteNamesInStmt(it, vForwardSubst) })
		}
		return inChild.copy(
			members = vInjectedProps + vInjectedBodyProps + vInheritedMethods + vSuperCopies + vOwnMembers,
			initBlocks = vParentInits + vOwnInits
			// superInterfaces unchanged: the parent NAME stays in the list and now
			// resolves to the synthesized interface.
		)
	}

	/* Rewrite `super.m(args)` → `this.m$super$Parent(args)` and `super.x` → `this.x`.
	Two bottom-up passes: the first marks super receivers and retargets calls (the
	callee is rebuilt before its CallExpr, so a marker keeps the super-ness visible);
	the second collapses remaining (non-call) super property accesses. */
	private fun rewriteSuperExpr(inE: Expr, inParentName: String): Expr {
		val vMarked = mapExpr(inE) { vE ->
			when {
				vE is DotExpr && (vE.obj as? NameExpr)?.name == "super" ->
					DotExpr(NameExpr("\$superRecv"), vE.name)
				vE is CallExpr && vE.callee is DotExpr && ((vE.callee as DotExpr).obj as? NameExpr)?.name == "\$superRecv" ->
					CallExpr(DotExpr(ThisExpr, "${(vE.callee as DotExpr).name}\$super\$$inParentName"), vE.args, vE.typeArgs)
				else -> vE
			}
		}
		return mapExpr(vMarked) { vE ->
			if (vE is DotExpr && (vE.obj as? NameExpr)?.name == "\$superRecv") DotExpr(ThisExpr, vE.name) else vE
		}
	}

	private fun rewriteSuperBlock(inB: Block, inParentName: String): Block =
		Block(inB.stmts.map { vS ->
			mapStmt(mapStmt(vS) { vE ->
				when {
					vE is DotExpr && (vE.obj as? NameExpr)?.name == "super" ->
						DotExpr(NameExpr("\$superRecv"), vE.name)
					vE is CallExpr && vE.callee is DotExpr && ((vE.callee as DotExpr).obj as? NameExpr)?.name == "\$superRecv" ->
						CallExpr(DotExpr(ThisExpr, "${(vE.callee as DotExpr).name}\$super\$$inParentName"), vE.args, vE.typeArgs)
					else -> vE
				}
			}) { vE ->
				if (vE is DotExpr && (vE.obj as? NameExpr)?.name == "\$superRecv") DotExpr(ThisExpr, vE.name) else vE
			}
		})

	private fun walkBlockOrNull(inB: Block?, inVisit: (Expr) -> Unit) {
		if (inB != null) walkBlock(inB, inVisit)
	}

	// ==================
	// MARK: Name substitution (forwarding ctor params → super-arg expressions)
	// ==================

	private fun substituteNames(inE: Expr, inMap: Map<String, Expr>): Expr {
		if (inMap.isEmpty()) return inE
		return mapExpr(inE) { if (it is NameExpr && it.name in inMap) inMap[it.name]!! else it }
	}

	private fun substituteNamesInStmt(inS: Stmt, inMap: Map<String, Expr>): Stmt {
		if (inMap.isEmpty()) return inS
		return mapStmt(inS) { if (it is NameExpr && it.name in inMap) inMap[it.name]!! else it }
	}

	// ==================
	// MARK: Extendable class → interface (+ $Impl)
	// ==================

	/* The polymorphic view of an extendable class: every non-private method as a
	signature, every stored property as a read-only interface property. Supertypes
	pass through (a parent class name resolves to ITS view, chaining the hierarchy). */
	private fun toInterface(inDecl: ClassDecl): InterfaceDecl {
		val vMethods = inDecl.members.filterIsInstance<FunDecl>()
			.filter { !it.isPrivate }
			.map { it.copy(body = null, isOverride = false) }
		val vProps = inDecl.ctorParams.filter { (it.isVal || it.isVar) && !it.isPrivate }
			.map { PropDecl(it.name, it.type, init = null, mutable = false) } +
			inDecl.members.filterIsInstance<PropDecl>()
				.filter { !it.isPrivate && it.getter == null }
				.map { PropDecl(it.name, it.type, init = null, mutable = false) }
		return InterfaceDecl(
			name = inDecl.name,
			methods = vMethods.distinctBy { it.name to it.params.size },
			properties = vProps.distinctBy { it.name },
			superInterfaces = inDecl.superInterfaces,
			isSealed = inDecl.isSealed,
			annotations = inDecl.annotations.filter { it.name != "RequireFree" }
		)
	}

	/* Hidden concrete class behind an `open` class — what `P(args)` instantiates. */
	private fun toImplClass(inDecl: ClassDecl): ClassDecl {
		val vMembers = inDecl.members.map { vM ->
			if (vM is FunDecl && !vM.isPrivate) vM.copy(isOverride = true) else vM
		}
		return ClassDecl(
			name = "${inDecl.name}\$Impl",
			isData = false,
			ctorParams = inDecl.ctorParams,
			members = vMembers,
			initBlocks = inDecl.initBlocks,
			superInterfaces = listOf(TypeRef(inDecl.name)),
			secondaryCtors = inDecl.secondaryCtors,
			annotations = inDecl.annotations,
			isInternal = inDecl.isInternal
		)
	}

	// ==================
	// MARK: Constructor-call rewriting
	// ==================

	/* True when a statement body contains a `super.` reference (parsed as the
	identifier `super` — KTC has no super token; any NameExpr("super") counts). */
	private fun bodyCallsSuper(inBody: Block?): Boolean {
		if (inBody == null) return false
		var vFound = false
		walkBlock(inBody) { if (it is NameExpr && it.name == "super") vFound = true }
		return vFound
	}

	private fun rewriteDecl(inDecl: Decl, inOpen: Set<String>, inAbstract: Set<String>): Decl {
		fun rw(e: Expr?): Expr? = e?.let { rewriteExpr(it, inOpen, inAbstract) }
		fun rwBlock(b: Block?): Block? = b?.let { Block(it.stmts.map { s -> rewriteStmt(s, inOpen, inAbstract) }) }
		return when (inDecl) {
			is FunDecl   -> inDecl.copy(
				body = rwBlock(inDecl.body),
				params = inDecl.params.map { it.copy(default = rw(it.default)) })
			is PropDecl  -> inDecl.copy(init = rw(inDecl.init), lazyInit = rwBlock(inDecl.lazyInit), setterBody = rwBlock(inDecl.setterBody), getter = rw(inDecl.getter))
			is ClassDecl -> inDecl.copy(
				members = inDecl.members.map { rewriteDecl(it, inOpen, inAbstract) },
				initBlocks = inDecl.initBlocks.map { rwBlock(it)!! },
				ctorParams = inDecl.ctorParams.map { it.copy(default = rw(it.default)) },
				secondaryCtors = inDecl.secondaryCtors.map { it.copy(body = rwBlock(it.body)!!, delegation = rewriteExpr(it.delegation, inOpen, inAbstract) as CallExpr) })
			is ObjectDecl -> inDecl.copy(members = inDecl.members.map { rewriteDecl(it, inOpen, inAbstract) })
			is EnumDecl   -> inDecl.copy(members = inDecl.members.map { rewriteDecl(it, inOpen, inAbstract) })
			is InterfaceDecl -> inDecl.copy(methods = inDecl.methods.map { rewriteDecl(it, inOpen, inAbstract) as FunDecl })
			else -> inDecl
		}
	}

	private fun rewriteStmt(inS: Stmt, inOpen: Set<String>, inAbstract: Set<String>): Stmt {
		fun rw(e: Expr?): Expr? = e?.let { rewriteExpr(it, inOpen, inAbstract) }
		fun rwB(b: Block): Block = Block(b.stmts.map { rewriteStmt(it, inOpen, inAbstract) })
		val vNew: Stmt = when (inS) {
			is ExprStmt    -> ExprStmt(rw(inS.expr)!!)
			is VarDeclStmt -> inS.copy(init = rw(inS.init), lazyInit = inS.lazyInit?.let { rwB(it) })
			is DestructuringDeclStmt -> inS.copy(init = rw(inS.init)!!)
			is AssignStmt  -> inS.copy(target = rw(inS.target)!!, value = rw(inS.value)!!)
			is ReturnStmt  -> ReturnStmt(rw(inS.value))
			is ThrowStmt   -> ThrowStmt(rw(inS.value)!!)
			is ForStmt     -> inS.copy(iter = rw(inS.iter)!!, body = rwB(inS.body))
			is WhileStmt   -> inS.copy(cond = rw(inS.cond)!!, body = rwB(inS.body))
			is DoWhileStmt -> inS.copy(body = rwB(inS.body), cond = rw(inS.cond)!!)
			is DeferStmt   -> inS.copy(body = rwB(inS.body))
			is TryStmt     -> inS.copy(body = rwB(inS.body),
				catches = inS.catches.map { it.copy(body = rwB(it.body)) },
				finallyBlock = inS.finallyBlock?.let { rwB(it) })
			else -> inS
		}
		vNew.line = inS.line; vNew.col = inS.col
		return vNew
	}

	private fun rewriteExpr(inE: Expr, inOpen: Set<String>, inAbstract: Set<String>): Expr {
		fun rw(e: Expr): Expr = rewriteExpr(e, inOpen, inAbstract)
		fun rwB(b: Block): Block = Block(b.stmts.map { rewriteStmt(it, inOpen, inAbstract) })
		return when (inE) {
			is CallExpr -> {
				val vName = (inE.callee as? NameExpr)?.name
				if (vName != null && vName in inAbstract)
					error("Cannot instantiate '$vName' — it is an abstract/sealed class")
				val vCallee = if (vName != null && vName in inOpen) NameExpr("$vName\$Impl") else rw(inE.callee)
				CallExpr(vCallee, inE.args.map { it.copy(expr = rw(it.expr)) }, inE.typeArgs)
			}
			is BinExpr     -> BinExpr(rw(inE.left), inE.op, rw(inE.right))
			is PrefixExpr  -> PrefixExpr(inE.op, rw(inE.expr))
			is PostfixExpr -> PostfixExpr(rw(inE.expr), inE.op)
			is DotExpr     -> DotExpr(rw(inE.obj), inE.name)
			is SafeDotExpr -> SafeDotExpr(rw(inE.obj), inE.name)
			is IndexExpr   -> IndexExpr(rw(inE.obj), rw(inE.index))
			is IfExpr      -> IfExpr(rw(inE.cond), rwB(inE.then), inE.els?.let { rwB(it) })
			is WhenExpr    -> WhenExpr(inE.subject?.let { rw(it) }, inE.branches.map { vB ->
				WhenBranch(vB.conds?.map { vC ->
					when (vC) {
						is ExprCond -> ExprCond(rw(vC.expr))
						is InCond   -> InCond(rw(vC.expr), vC.negated)
						else        -> vC
					}
				}, rwB(vB.body))
			})
			is NotNullExpr -> NotNullExpr(rw(inE.expr))
			is ElvisExpr   -> ElvisExpr(rw(inE.left), rw(inE.right))
			is IsCheckExpr -> IsCheckExpr(rw(inE.expr), inE.type, inE.negated)
			is CastExpr    -> CastExpr(rw(inE.expr), inE.type, inE.safe)
			is LambdaExpr  -> inE.copy(body = inE.body.map { rewriteStmt(it, inOpen, inAbstract) })
			is StrTemplateExpr -> StrTemplateExpr(inE.parts.map { vP ->
				if (vP is ExprPart) ExprPart(rw(vP.expr)) else vP
			})
			else -> inE
		}
	}

	// ==================
	// MARK: Generic structural mappers
	// ==================

	/* Rebuild an expression bottom-up, applying [inF] to every (rebuilt) node. */
	private fun mapExpr(inE: Expr, inF: (Expr) -> Expr): Expr {
		fun m(e: Expr): Expr = mapExpr(e, inF)
		fun mB(b: Block): Block = Block(b.stmts.map { mapStmt(it, inF) })
		val vRebuilt: Expr = when (inE) {
			is CallExpr    -> CallExpr(m(inE.callee), inE.args.map { it.copy(expr = m(it.expr)) }, inE.typeArgs)
			is BinExpr     -> BinExpr(m(inE.left), inE.op, m(inE.right))
			is PrefixExpr  -> PrefixExpr(inE.op, m(inE.expr))
			is PostfixExpr -> PostfixExpr(m(inE.expr), inE.op)
			is DotExpr     -> DotExpr(m(inE.obj), inE.name)
			is SafeDotExpr -> SafeDotExpr(m(inE.obj), inE.name)
			is IndexExpr   -> IndexExpr(m(inE.obj), m(inE.index))
			is IfExpr      -> IfExpr(m(inE.cond), mB(inE.then), inE.els?.let { mB(it) })
			is WhenExpr    -> WhenExpr(inE.subject?.let { m(it) }, inE.branches.map { vB ->
				WhenBranch(vB.conds?.map { vC ->
					when (vC) {
						is ExprCond -> ExprCond(m(vC.expr))
						is InCond   -> InCond(m(vC.expr), vC.negated)
						else        -> vC
					}
				}, mB(vB.body))
			})
			is NotNullExpr -> NotNullExpr(m(inE.expr))
			is ElvisExpr   -> ElvisExpr(m(inE.left), m(inE.right))
			is IsCheckExpr -> IsCheckExpr(m(inE.expr), inE.type, inE.negated)
			is CastExpr    -> CastExpr(m(inE.expr), inE.type, inE.safe)
			is LambdaExpr  -> inE.copy(body = inE.body.map { mapStmt(it, inF) })
			is StrTemplateExpr -> StrTemplateExpr(inE.parts.map { vP ->
				if (vP is ExprPart) ExprPart(m(vP.expr)) else vP
			})
			else -> inE
		}
		return inF(vRebuilt)
	}

	/* Rebuild a statement bottom-up, applying [inF] to every expression node. */
	private fun mapStmt(inS: Stmt, inF: (Expr) -> Expr): Stmt {
		fun m(e: Expr): Expr = mapExpr(e, inF)
		fun mB(b: Block): Block = Block(b.stmts.map { mapStmt(it, inF) })
		val vNew: Stmt = when (inS) {
			is ExprStmt    -> ExprStmt(m(inS.expr))
			is VarDeclStmt -> inS.copy(init = inS.init?.let { m(it) }, lazyInit = inS.lazyInit?.let { mB(it) })
			is DestructuringDeclStmt -> inS.copy(init = m(inS.init))
			is AssignStmt  -> inS.copy(target = m(inS.target), value = m(inS.value))
			is ReturnStmt  -> ReturnStmt(inS.value?.let { m(it) })
			is ThrowStmt   -> ThrowStmt(m(inS.value))
			is ForStmt     -> inS.copy(iter = m(inS.iter), body = mB(inS.body))
			is WhileStmt   -> inS.copy(cond = m(inS.cond), body = mB(inS.body))
			is DoWhileStmt -> inS.copy(body = mB(inS.body), cond = m(inS.cond))
			is DeferStmt   -> inS.copy(body = mB(inS.body))
			is TryStmt     -> inS.copy(body = mB(inS.body),
				catches = inS.catches.map { it.copy(body = mB(it.body)) },
				finallyBlock = inS.finallyBlock?.let { mB(it) })
			else -> inS
		}
		vNew.line = inS.line; vNew.col = inS.col
		return vNew
	}

	/* Minimal read-only expression walker used by bodyCallsSuper. */
	private fun walkBlock(inBlock: Block, inVisit: (Expr) -> Unit) {
		inBlock.stmts.forEach { walkStmt(it, inVisit) }
	}

	private fun walkStmt(inS: Stmt, inVisit: (Expr) -> Unit) {
		when (inS) {
			is ExprStmt -> walkExpr(inS.expr, inVisit)
			is VarDeclStmt -> { walkExpr(inS.init, inVisit); inS.lazyInit?.let { walkBlock(it, inVisit) } }
			is DestructuringDeclStmt -> walkExpr(inS.init, inVisit)
			is AssignStmt -> { walkExpr(inS.target, inVisit); walkExpr(inS.value, inVisit) }
			is ReturnStmt -> walkExpr(inS.value, inVisit)
			is ThrowStmt -> walkExpr(inS.value, inVisit)
			is ForStmt -> { walkExpr(inS.iter, inVisit); walkBlock(inS.body, inVisit) }
			is WhileStmt -> { walkExpr(inS.cond, inVisit); walkBlock(inS.body, inVisit) }
			is DoWhileStmt -> { walkBlock(inS.body, inVisit); walkExpr(inS.cond, inVisit) }
			is DeferStmt -> walkBlock(inS.body, inVisit)
			is TryStmt -> {
				walkBlock(inS.body, inVisit)
				inS.catches.forEach { walkBlock(it.body, inVisit) }
				inS.finallyBlock?.let { walkBlock(it, inVisit) }
			}
			else -> {}
		}
	}

	private fun walkExpr(inE: Expr?, inVisit: (Expr) -> Unit) {
		if (inE == null) return
		inVisit(inE)
		when (inE) {
			is CallExpr -> { walkExpr(inE.callee, inVisit); inE.args.forEach { walkExpr(it.expr, inVisit) } }
			is BinExpr -> { walkExpr(inE.left, inVisit); walkExpr(inE.right, inVisit) }
			is PrefixExpr -> walkExpr(inE.expr, inVisit)
			is PostfixExpr -> walkExpr(inE.expr, inVisit)
			is DotExpr -> walkExpr(inE.obj, inVisit)
			is SafeDotExpr -> walkExpr(inE.obj, inVisit)
			is IndexExpr -> { walkExpr(inE.obj, inVisit); walkExpr(inE.index, inVisit) }
			is IfExpr -> { walkExpr(inE.cond, inVisit); walkBlock(inE.then, inVisit); inE.els?.let { walkBlock(it, inVisit) } }
			is WhenExpr -> {
				walkExpr(inE.subject, inVisit)
				inE.branches.forEach { vB ->
					vB.conds?.forEach { if (it is ExprCond) walkExpr(it.expr, inVisit); if (it is InCond) walkExpr(it.expr, inVisit) }
					walkBlock(vB.body, inVisit)
				}
			}
			is NotNullExpr -> walkExpr(inE.expr, inVisit)
			is ElvisExpr -> { walkExpr(inE.left, inVisit); walkExpr(inE.right, inVisit) }
			is IsCheckExpr -> walkExpr(inE.expr, inVisit)
			is CastExpr -> walkExpr(inE.expr, inVisit)
			is LambdaExpr -> inE.body.forEach { walkStmt(it, inVisit) }
			is StrTemplateExpr -> inE.parts.forEach { if (it is ExprPart) walkExpr(it.expr, inVisit) }
			else -> {}
		}
	}
}
