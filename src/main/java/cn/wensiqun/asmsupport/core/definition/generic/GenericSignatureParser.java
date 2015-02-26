/**
 * Unuse now.
 */

package cn.wensiqun.asmsupport.core.definition.generic;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import cn.wensiqun.asmsupport.core.definition.generic.GenericSignature.ArrayTypeSignature;
import cn.wensiqun.asmsupport.core.definition.generic.GenericSignature.BaseTypeSignature;
import cn.wensiqun.asmsupport.core.definition.generic.GenericSignature.ClassSignature;
import cn.wensiqun.asmsupport.core.definition.generic.GenericSignature.ClassTypeSignature;
import cn.wensiqun.asmsupport.core.definition.generic.GenericSignature.FieldTypeSignature;
import cn.wensiqun.asmsupport.core.definition.generic.GenericSignature.FormalTypeParameter;
import cn.wensiqun.asmsupport.core.definition.generic.GenericSignature.MethodTypeSignature;
import cn.wensiqun.asmsupport.core.definition.generic.GenericSignature.SimpleClassTypeSignature;
import cn.wensiqun.asmsupport.core.definition.generic.GenericSignature.TypeArgument;
import cn.wensiqun.asmsupport.core.definition.generic.GenericSignature.TypeSignature;
import cn.wensiqun.asmsupport.core.definition.generic.GenericSignature.TypeVariableSignature;
import cn.wensiqun.asmsupport.core.utils.asm.ClassAdapter;
import cn.wensiqun.asmsupport.org.objectweb.asm.ClassReader;
import cn.wensiqun.asmsupport.org.objectweb.asm.FieldVisitor;
import cn.wensiqun.asmsupport.org.objectweb.asm.MethodVisitor;

/**
 * Parses the generic signature attribute as defined in the JVM spec.
 * 
 * @author Adrian Colyer
 * @author Andy Clement
 */
public class GenericSignatureParser {

	private String inputString;
	private String[] tokenStream; // for parse in flight
	private int tokenIndex = 0;

	/**
	 * AMC. Parse the signature string interpreting it as a ClassSignature according to the grammar defined in Section 4.4.4 of the
	 * JVM specification.
	 */
	public GenericSignature.ClassSignature parseAsClassSignature(String sig) {
		this.inputString = sig;
		tokenStream = tokenize(sig);
		tokenIndex = 0;
		GenericSignature.ClassSignature classSig = new GenericSignature.ClassSignature();
		// FormalTypeParameters-opt
		if (maybeEat("<")) {
			List<FormalTypeParameter> formalTypeParametersList = new ArrayList<FormalTypeParameter>();
			do {
				formalTypeParametersList.add(parseFormalTypeParameter());
			} while (!maybeEat(">"));
			classSig.formalTypeParameters = new FormalTypeParameter[formalTypeParametersList.size()];
			formalTypeParametersList.toArray(classSig.formalTypeParameters);
		}
		classSig.superclassSignature = parseClassTypeSignature();
		List<ClassTypeSignature> superIntSigs = new ArrayList<ClassTypeSignature>();
		while (tokenIndex < tokenStream.length) {
			superIntSigs.add(parseClassTypeSignature());
		}
		classSig.superInterfaceSignatures = new ClassTypeSignature[superIntSigs.size()];
		superIntSigs.toArray(classSig.superInterfaceSignatures);
		return classSig;
	}

	/**
	 * AMC. Parse the signature string interpreting it as a MethodTypeSignature according to the grammar defined in Section 4.4.4 of
	 * the JVM specification.
	 */
	public MethodTypeSignature parseAsMethodSignature(String sig) {
		this.inputString = sig;
		tokenStream = tokenize(sig);
		tokenIndex = 0;
		FormalTypeParameter[] formals = new FormalTypeParameter[0];
		TypeSignature returnType = null;
		// FormalTypeParameters-opt
		if (maybeEat("<")) {
			List<FormalTypeParameter> formalTypeParametersList = new ArrayList<FormalTypeParameter>();
			do {
				formalTypeParametersList.add(parseFormalTypeParameter());
			} while (!maybeEat(">"));
			formals = new FormalTypeParameter[formalTypeParametersList.size()];
			formalTypeParametersList.toArray(formals);
		}
		// Parameters
		eat("(");
		List<TypeSignature> paramList = new ArrayList<TypeSignature>();
		while (!maybeEat(")")) {
			FieldTypeSignature fsig = parseFieldTypeSignature(true);
			if (fsig != null) {
				paramList.add(fsig);
			} else {
				paramList.add(new GenericSignature.BaseTypeSignature(eatIdentifier()));
			}
		}
		TypeSignature[] params = new TypeSignature[paramList.size()];
		paramList.toArray(params);
		// return type
		returnType = parseFieldTypeSignature(true);
		if (returnType == null)
			returnType = new GenericSignature.BaseTypeSignature(eatIdentifier());
		// throws
		List<FieldTypeSignature> throwsList = new ArrayList<FieldTypeSignature>();
		while (maybeEat("^")) {
			FieldTypeSignature fsig = parseFieldTypeSignature(false);
			throwsList.add(fsig);
		}
		FieldTypeSignature[] throwsSigs = new FieldTypeSignature[throwsList.size()];
		throwsList.toArray(throwsSigs);
		return new GenericSignature.MethodTypeSignature(formals, params, returnType, throwsSigs);
	}

	/**
	 * AMC. Parse the signature string interpreting it as a FieldTypeSignature according to the grammar defined in Section 4.4.4 of
	 * the JVM specification.
	 */
	public FieldTypeSignature parseAsFieldSignature(String sig) {
		this.inputString = sig;
		tokenStream = tokenize(sig);
		tokenIndex = 0;
		return parseFieldTypeSignature(false);
	}

	private FormalTypeParameter parseFormalTypeParameter() {
		FormalTypeParameter ftp = new FormalTypeParameter();
		// Identifier
		ftp.identifier = eatIdentifier();
		// ClassBound
		eat(":");
		ftp.classBound = parseFieldTypeSignature(true);
		if (ftp.classBound == null) {
			ftp.classBound = new ClassTypeSignature("Ljava/lang/Object;", "Ljava/lang/Object");
		}
		// Optional InterfaceBounds
		List optionalBounds = new ArrayList();
		while (maybeEat(":")) {
			optionalBounds.add(parseFieldTypeSignature(false));
		}
		ftp.interfaceBounds = new FieldTypeSignature[optionalBounds.size()];
		optionalBounds.toArray(ftp.interfaceBounds);
		return ftp;
	}

	private FieldTypeSignature parseFieldTypeSignature(boolean isOptional) {
		if (isOptional) {
			// anything other than 'L', 'T' or '[' and we're out of here
			if (!tokenStream[tokenIndex].startsWith("L") && !tokenStream[tokenIndex].startsWith("T")
					&& !tokenStream[tokenIndex].startsWith("[")) {
				return null;
			}
		}
		if (maybeEat("[")) {
			return parseArrayTypeSignature();
		} else if (tokenStream[tokenIndex].startsWith("L")) {
			return parseClassTypeSignature();
		} else if (tokenStream[tokenIndex].startsWith("T")) {
			return parseTypeVariableSignature();
		} else {
			throw new IllegalStateException("Expecting [,L, or T, but found " + tokenStream[tokenIndex] + " while unpacking "
					+ inputString);
		}
	}

	private ArrayTypeSignature parseArrayTypeSignature() {
		// opening [ already eaten
		FieldTypeSignature fieldType = parseFieldTypeSignature(true);
		if (fieldType != null) {
			return new ArrayTypeSignature(fieldType);
		} else {
			// must be BaseType array
			return new ArrayTypeSignature(new BaseTypeSignature(eatIdentifier()));
		}
	}

	// L PackageSpecifier* SimpleClassTypeSignature ClassTypeSignature* ;
	private ClassTypeSignature parseClassTypeSignature() {
		SimpleClassTypeSignature outerType = null;
		SimpleClassTypeSignature[] nestedTypes = new SimpleClassTypeSignature[0];
		StringBuffer ret = new StringBuffer();
		String identifier = eatIdentifier();
		ret.append(identifier);
		while (maybeEat("/")) {
			ret.append("/"); // dont forget this...
			ret.append(eatIdentifier());
		}
		identifier = ret.toString();
		// now we have either a "." indicating the start of a nested type,
		// or a "<" indication type arguments, or ";" and we are done.
		while (!maybeEat(";")) {
			if (maybeEat(".")) {
				// outer type completed
				outerType = new SimpleClassTypeSignature(identifier);
				List nestedTypeList = new ArrayList();
				do {
					ret.append(".");
					SimpleClassTypeSignature sig = parseSimpleClassTypeSignature();
					ret.append(sig.toString());
					nestedTypeList.add(sig);
				} while (maybeEat("."));
				nestedTypes = new SimpleClassTypeSignature[nestedTypeList.size()];
				nestedTypeList.toArray(nestedTypes);
			} else if (tokenStream[tokenIndex].equals("<")) {
				ret.append("<");
				TypeArgument[] tArgs = maybeParseTypeArguments();
				for (int i = 0; i < tArgs.length; i++) {
					ret.append(tArgs[i].toString());
				}
				ret.append(">");
				outerType = new SimpleClassTypeSignature(identifier, tArgs);
				// now parse possible nesteds...
				List nestedTypeList = new ArrayList();
				while (maybeEat(".")) {
					ret.append(".");
					SimpleClassTypeSignature sig = parseSimpleClassTypeSignature();
					ret.append(sig.toString());
					nestedTypeList.add(sig);
				}
				nestedTypes = new SimpleClassTypeSignature[nestedTypeList.size()];
				nestedTypeList.toArray(nestedTypes);
			} else {
				throw new IllegalStateException("Expecting .,<, or ;, but found " + tokenStream[tokenIndex] + " while unpacking "
						+ inputString);
			}
		}
		ret.append(";");
		if (outerType == null)
			outerType = new SimpleClassTypeSignature(ret.toString());
		return new ClassTypeSignature(ret.toString(), outerType, nestedTypes);
	}

	private SimpleClassTypeSignature parseSimpleClassTypeSignature() {
		String identifier = eatIdentifier();
		TypeArgument[] tArgs = maybeParseTypeArguments();
		if (tArgs != null) {
			return new SimpleClassTypeSignature(identifier, tArgs);
		} else {
			return new SimpleClassTypeSignature(identifier);
		}
	}

	private TypeArgument parseTypeArgument() {
		boolean isPlus = false;
		boolean isMinus = false;
		if (maybeEat("*")) {
			return new TypeArgument();
		} else if (maybeEat("+")) {
			isPlus = true;
		} else if (maybeEat("-")) {
			isMinus = true;
		}
		FieldTypeSignature sig = parseFieldTypeSignature(false);
		return new TypeArgument(isPlus, isMinus, sig);
	}

	private TypeArgument[] maybeParseTypeArguments() {
		if (maybeEat("<")) {
			List typeArgs = new ArrayList();
			do {
				TypeArgument arg = parseTypeArgument();
				typeArgs.add(arg);
			} while (!maybeEat(">"));
			TypeArgument[] tArgs = new TypeArgument[typeArgs.size()];
			typeArgs.toArray(tArgs);
			return tArgs;
		} else {
			return null;
		}
	}

	private TypeVariableSignature parseTypeVariableSignature() {
		TypeVariableSignature tv = new TypeVariableSignature(eatIdentifier());
		eat(";");
		return tv;
	}

	private boolean maybeEat(String token) {
		if (tokenStream.length <= tokenIndex)
			return false;
		if (tokenStream[tokenIndex].equals(token)) {
			tokenIndex++;
			return true;
		}
		return false;
	}

	private void eat(String token) {
		if (!tokenStream[tokenIndex].equals(token)) {
			throw new IllegalStateException("Expecting " + token + " but found " + tokenStream[tokenIndex] + " while unpacking "
					+ inputString);
		}
		tokenIndex++;
	}

	private String eatIdentifier() {
		return tokenStream[tokenIndex++];
	}

	/**
	 * non-private for test visibility Splits a string containing a generic signature into tokens for consumption by the parser.
	 */
	public String[] tokenize(String signatureString) {
		char[] chars = signatureString.toCharArray();
		int index = 0;
		List<String> tokens = new ArrayList<String>();
		StringBuffer identifier = new StringBuffer();
		boolean inParens = false;
		boolean inArray = false;
		boolean couldSeePrimitive = false;
		do {
			switch (chars[index]) {
			case '<':
				if (identifier.length() > 0)
					tokens.add(identifier.toString());
				identifier = new StringBuffer();
				tokens.add("<");
				break;
			case '>':
				if (identifier.length() > 0)
					tokens.add(identifier.toString());
				identifier = new StringBuffer();
				tokens.add(">");
				break;
			case ':':
				if (identifier.length() > 0)
					tokens.add(identifier.toString());
				identifier = new StringBuffer();
				tokens.add(":");
				break;
			case '/':
				if (identifier.length() > 0)
					tokens.add(identifier.toString());
				identifier = new StringBuffer();
				tokens.add("/");
				couldSeePrimitive = false;
				break;
			case ';':
				if (identifier.length() > 0)
					tokens.add(identifier.toString());
				identifier = new StringBuffer();
				tokens.add(";");
				couldSeePrimitive = true;
				inArray = false;
				break;
			case '^':
				if (identifier.length() > 0)
					tokens.add(identifier.toString());
				identifier = new StringBuffer();
				tokens.add("^");
				break;
			case '+':
				tokens.add("+");
				break;
			case '-':
				tokens.add("-");
				break;
			case '*':
				tokens.add("*");
				break;
			case '.':
				if (identifier.length() > 0)
					tokens.add(identifier.toString());
				identifier = new StringBuffer();
				couldSeePrimitive = false;
				tokens.add(".");
				break;
			case '(':
				tokens.add("(");
				inParens = true;
				couldSeePrimitive = true;
				break;
			case ')':
				tokens.add(")");
				inParens = false;
				break;
			case '[':
				tokens.add("[");
				couldSeePrimitive = true;
				inArray = true;
				break;
			case 'B':
			case 'C':
			case 'D':
			case 'F':
			case 'I':
			case 'J':
			case 'S':
			case 'V':
			case 'Z':
				if ((inParens || inArray) && couldSeePrimitive && identifier.length() == 0) {
					tokens.add(new String("" + chars[index]));
				} else {
					identifier.append(chars[index]);
				}
				inArray = false;
				break;
			case 'L':
				couldSeePrimitive = false;
				// deliberate fall-through
			default:
				identifier.append(chars[index]);
			}
		} while ((++index) < chars.length);
		if (identifier.length() > 0)
			tokens.add(identifier.toString());
		String[] tokenArray = new String[tokens.size()];
		tokens.toArray(tokenArray);
		return tokenArray;
	}
	
	public static ClassSignature getClassSignature(Class<?> clazz){
		URL resource = Thread.currentThread().getContextClassLoader().getResource(clazz.getName().replace('.', '/'));
		try {
			InputStream in = resource.openStream();
            
			try{
				ClassReader classReader = new ClassReader(in);
				SignatureGetter sg = new SignatureGetter();
	            GenericSignatureParser parser = new GenericSignatureParser();
	            classReader.accept(sg, ClassReader.SKIP_DEBUG);
	            return parser.parseAsClassSignature(sg.getClassSignature());
			} finally {
                in.close();
            }
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}
	
	public static class SignatureGetter extends ClassAdapter {

		private String classSignature;
		
		public String getClassSignature() {
			return classSignature;
		}

		@Override
		public void visit(int version, int access, String name,
				String signature, String superName, String[] interfaces) {
			classSignature = signature;	
		}

		@Override
		public FieldVisitor visitField(int access, String name, String desc,
				String signature, Object value) {
			return super.visitField(access, name, desc, signature, value);
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String desc,
				String signature, String[] exceptions) {
			return super.visitMethod(access, name, desc, signature, exceptions);
		}
		
	}

}
