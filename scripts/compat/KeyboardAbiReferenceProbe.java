import dalvik.system.DexClassLoader;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.OpCodeMatchType;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.*;
import org.luckypray.dexkit.wrap.*;

/** Generate name-independent bytecode/call/field witnesses from a verified reference APK. */
public final class KeyboardAbiReferenceProbe {
    static DexKitBridge bridge;
    static ClassLoader loader;
    static final Map<String, JSONObject> anchors = new LinkedHashMap<>();
    static final Map<String, Boolean> unique = new HashMap<>();
    static final Map<String, JSONObject> rules = new LinkedHashMap<>();
    static final Map<String, String> owners = new HashMap<>();

    static String coarse(String type) {
        if (!type.contains(".") || type.startsWith("java.") || type.startsWith("android.") ||
                type.startsWith("com.mi.") || type.startsWith("kotlin.jvm.functions.")) return type;
        return "*";
    }
    static JSONObject anchor(MethodData method, String scope) throws Exception {
        if (method.getOpCodes().isEmpty()) return null;
        String id = method.getDescriptor() + "@" + scope;
        if (unique.containsKey(id)) return unique.get(id) ? anchors.get(id) : null;
        MethodMatcher matcher = new MethodMatcher().paramCount(method.getParamCount())
                .opCodes(method.getOpCodes(), OpCodeMatchType.Equals, null);
        if (!method.getUsingStrings().isEmpty()) matcher.usingStrings(method.getUsingStrings(), StringMatchType.Equals, false);
        if (scope != null) matcher.declaredClass(method.getClassName());
        List<MethodData> matches = bridge.findMethod(new FindMethod().matcher(matcher));
        List<MethodData> filtered = new ArrayList<>();
        for (MethodData item : matches) if (shape(method).equals(shape(item))) filtered.add(item);
        boolean ok = filtered.size() == 1 && filtered.get(0).getDescriptor().equals(method.getDescriptor());
        unique.put(id, ok);
        if (!ok) return null;
        JSONObject result = new JSONObject().put("opcodes", new JSONArray(method.getOpCodes()))
                .put("strings", new JSONArray(method.getUsingStrings())).put("shape", shape(method));
        if (scope != null) result.put("scope", scope);
        anchors.put(id, result);
        return result;
    }
    static String shape(MethodData method) {
        List<String> params = new ArrayList<>();
        for (String value : method.getParamTypeNames()) params.add(coarse(value));
        return Modifier.isStatic(method.getModifiers()) + ":" + coarse(method.getReturnTypeName()) + ":" + String.join(",", params);
    }
    static JSONObject recipe(MethodData method, String scope, String kind, int index) throws Exception {
        JSONObject ref = anchor(method, scope);
        if (ref == null) return null;
        return new JSONObject().put("anchor", method.getDescriptor() + "@" + scope).put("extract", kind).put("index", index);
    }
    static List<MethodData> ranked(Collection<MethodData> methods) {
        List<MethodData> values = new ArrayList<>(methods);
        values.sort(Comparator.comparingInt((MethodData m) -> m.getUsingStrings().isEmpty() ? 1 : 0)
                .thenComparingInt(m -> m.getOpCodes().size()));
        return values;
    }
    static JSONObject classRule(String name) throws Exception {
        ClassData type = bridge.getClassData(name);
        if (type == null) throw new IllegalStateException("Reference class missing: " + name);
        // Platform/service names survive obfuscation; all short R8 names use witnesses.
        if (name.startsWith("com.mi.") || name.startsWith("androidx.preference.") || name.startsWith("miuix.preference."))
            return new JSONObject().put("stable", type.getDescriptor());
        for (MethodData method : ranked(type.getMethods())) {
            JSONObject value = recipe(method, null, "owner", -1);
            if (value != null) return value;
        }
        for (MethodData target : type.getMethods()) for (MethodData caller : ranked(target.getCallers())) {
            List<MethodData> invokes = caller.getInvokes();
            for (int i=0; i<invokes.size(); i++) if (invokes.get(i).getClassName().equals(name)) {
                JSONObject value = recipe(caller, null, "invokeOwner", i);
                if (value != null) return value;
            }
        }
        for (FieldData field : type.getFields()) {
            List<MethodData> uses = new ArrayList<>(field.getReaders()); uses.addAll(field.getWriters());
            for (MethodData caller : ranked(uses)) {
                List<UsingFieldData> fields = caller.getUsingFields();
                for (int i=0; i<fields.size(); i++) if (fields.get(i).getField().getClassName().equals(name)) {
                    JSONObject value = recipe(caller, null, "fieldOwner", i);
                    if (value != null) return value;
                }
            }
        }
        // Some marker interfaces appear only as parameters/return values.
        List<MethodData> references = bridge.findMethod(new FindMethod().matcher(new MethodMatcher().returnType(name)));
        for (MethodData method : ranked(references)) {
            JSONObject value = recipe(method, null, "returnType", -1);
            if (value != null) return value;
        }
        references = bridge.findMethod(new FindMethod().matcher(new MethodMatcher().addParamType(name)));
        for (MethodData method : ranked(references)) {
            JSONObject value = recipe(method, null, "parameter", method.getParamTypeNames().indexOf(name));
            if (value != null) return value;
        }
        throw new IllegalStateException("No unique class witness: " + name);
    }
    static JSONObject methodRule(MethodData target, String alias) throws Exception {
        String scope = owners.get(target.getClassName());
        JSONObject value = recipe(target, scope, "method", -1);
        if (value != null) return value;
        for (MethodData caller : ranked(target.getCallers())) {
            List<MethodData> invokes = caller.getInvokes();
            for (int i=0; i<invokes.size(); i++) if (invokes.get(i).getDescriptor().equals(target.getDescriptor())) {
                value = recipe(caller, owners.get(caller.getClassName()), "invoke", i);
                if (value != null) return value;
            }
        }
        // DEX may encode an inherited interface call against the requested subtype.
        String requestedOwner = alias.substring(2, alias.indexOf('#'));
        String virtualDescriptor = "L" + requestedOwner.replace('.', '/') + ";->" + target.getName() +
                target.getDescriptor().substring(target.getDescriptor().indexOf('('));
        MethodData virtual = bridge.getMethodData(virtualDescriptor);
        if (virtual != null && !virtual.getDescriptor().equals(target.getDescriptor())) {
            for (MethodData caller : ranked(virtual.getCallers())) {
                List<MethodData> invokes = caller.getInvokes();
                for (int i=0;i<invokes.size();i++) if(invokes.get(i).getDescriptor().equals(virtualDescriptor)) {
                    value=recipe(caller,owners.get(caller.getClassName()),"invoke",i);
                    if(value!=null) return value;
                }
            }
        }
        Class<?> requested=Class.forName(requestedOwner,false,loader);
        List<Class<?>> parents=new ArrayList<>(Arrays.asList(requested.getInterfaces()));
        if(requested.getSuperclass()!=null) parents.add(requested.getSuperclass());
        Set<Class<?>> visited=new HashSet<>();
        for(int p=0;p<parents.size();p++) {
            Class<?> parent=parents.get(p); if(!visited.add(parent)) continue;
            parents.addAll(Arrays.asList(parent.getInterfaces()));
            if(parent.getSuperclass()!=null) parents.add(parent.getSuperclass());
            String ref="L"+parent.getName().replace('.','/')+";->"+target.getName()+target.getDescriptor().substring(target.getDescriptor().indexOf('('));
            MethodData inherited=bridge.getMethodData(ref); if(inherited==null) continue;
            for(MethodData caller:ranked(inherited.getCallers())) {
                List<MethodData> invokes=caller.getInvokes();
                for(int i=0;i<invokes.size();i++) if(invokes.get(i).getDescriptor().equals(ref)) {
                    value=recipe(caller,owners.get(caller.getClassName()),"invoke",i);
                    if(value!=null) return value;
                }
            }
        }
        // Unique abstract signatures require no implementation bytecode.
        List<MethodData> same = new ArrayList<>();
        ClassData owner = target.getDeclaredClass();
        for (MethodData method : owner.getMethods()) if (shape(method).equals(shape(target))) same.add(method);
        if (same.size()==1 && scope!=null) return new JSONObject().put("owner",scope).put("shape",shape(target));
        throw new IllegalStateException("No unique method witness: " + alias + " -> " + target.getDescriptor());
    }
    static JSONObject fieldRule(FieldData target) throws Exception {
        List<MethodData> uses = new ArrayList<>(target.getWriters()); uses.addAll(target.getReaders());
        for (MethodData caller : ranked(uses)) {
            List<UsingFieldData> fields = caller.getUsingFields();
            for (int i=0; i<fields.size(); i++) if (fields.get(i).getField().getDescriptor().equals(target.getDescriptor())) {
                JSONObject value = recipe(caller, owners.get(caller.getClassName()), "field", i);
                if (value!=null) return value;
            }
        }
        throw new IllegalStateException("No unique field witness: " + target.getDescriptor());
    }
    static String read(String path) throws Exception {
        try (InputStream in=new FileInputStream(path); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] data=new byte[8192]; int n; while((n=in.read(data))!=-1) out.write(data,0,n);
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    public static void main(String[] args) {
        try { run(args); } catch (Throwable failure) { failure.printStackTrace(System.out); System.exit(1); }
    }
    static void run(String[] args) throws Exception {
        System.out.println("Loading reference probe");
        File cpp = new File(args[0], "libc++_shared.so");
        if (cpp.isFile()) System.load(cpp.getAbsolutePath());
        System.load(args[0]+"/libdexkit.so");
        System.out.println("Native libraries loaded");
        loader=new DexClassLoader(args[1],args[2],null,ClassLoader.getSystemClassLoader().getParent());
        JSONObject manifest=new JSONObject(read(args[3]));
        for (Iterator<String> groups=manifest.keys();groups.hasNext();) {
            JSONArray classes=manifest.getJSONObject(groups.next()).getJSONArray("classes");
            for(int i=0;i<classes.length();i++) owners.put(classes.getString(i),classes.getString(i));
        }
        for (Iterator<String> groups=manifest.keys();groups.hasNext();) {
            JSONObject methods=manifest.getJSONObject(groups.next()).getJSONObject("methods");
            for(Iterator<String> types=methods.keys();types.hasNext();) {
                String name=types.next(); Class<?> type=Class.forName(name,false,loader);
                Set<String> names=new HashSet<>(Arrays.asList(methods.getString(name).split(" ")));
                for(Method method:type.getMethods()) if(names.contains(method.getName())) {
                    String owner=method.getDeclaringClass().getName(); owners.put(owner,owner);
                }
            }
        }
        try(DexKitBridge opened=DexKitBridge.create(args[1])) {
            System.out.println("Reference DEX opened");
            bridge=opened;
            for(String name:new TreeSet<>(owners.keySet())) {
                JSONObject rule=classRule(name).put("kind",bridge.getClassData(name).getModifiers() & (0x0200|0x0400|0x4000));
                rules.put("c:"+name,rule); System.out.println("CLASS "+name);
            }
            JSONObject groupKeys=new JSONObject(), original=new JSONObject();
            for(Iterator<String> groups=manifest.keys();groups.hasNext();) {
                String group=groups.next(); JSONObject spec=manifest.getJSONObject(group); JSONArray keys=new JSONArray();
                JSONArray classes=spec.getJSONArray("classes");
                for(int i=0;i<classes.length();i++) {String name=classes.getString(i);keys.put("c:"+name);original.put("c:"+name,bridge.getClassData(name).getDescriptor());}
                JSONObject methods=spec.getJSONObject("methods");
                for(Iterator<String> types=methods.keys();types.hasNext();) {
                    String name=types.next(); Class<?> type=Class.forName(name,false,loader);
                    for(String methodName:methods.getString(name).split(" ")) {
                        boolean found=false;
                        for(Method method:type.getMethods()) if(method.getName().equals(methodName)) {
                            found=true; MethodData data=bridge.getMethodData(method); String descriptor=new DexMethod(method).toString();
                            String id="m:"+name+"#"+methodName+descriptor.substring(descriptor.indexOf('('));
                            if(!rules.containsKey(id)) rules.put(id,methodRule(data,id).put("static",Modifier.isStatic(data.getModifiers()))); keys.put(id); original.put(id,descriptor);
                        }
                        if(!found) throw new IllegalStateException("Method missing: "+name+"#"+methodName);
                    }
                }
                JSONObject fields=spec.getJSONObject("fields");
                for(Iterator<String> types=fields.keys();types.hasNext();) {
                    String name=types.next(); Class<?> type=Class.forName(name,false,loader);
                    for(String fieldName:fields.getString(name).split(" ")) {
                        Field field=type.getDeclaredField(fieldName); FieldData data=bridge.getFieldData(field);
                        String id="f:"+name+"#"+fieldName; if(!rules.containsKey(id)) rules.put(id,fieldRule(data).put("static",Modifier.isStatic(data.getModifiers())));keys.put(id);original.put(id,data.getDescriptor());
                    }
                }
                groupKeys.put(group,keys);System.out.println("GROUP "+group+" "+keys.length());
            }
            JSONObject output=new JSONObject().put("format",1).put("groups",groupKeys).put("rules",new JSONObject(rules))
                    .put("anchors",new JSONObject(anchors)).put("reference",original);
            try(Writer writer=new OutputStreamWriter(new FileOutputStream(args[4]),StandardCharsets.UTF_8)) {writer.write(output.toString(2));}
            System.out.println("DONE "+rules.size()+" symbols; "+anchors.size()+" witnesses");
        }
    }
}
