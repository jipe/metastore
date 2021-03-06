package refstore.wiring;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

import net.jcip.annotations.GuardedBy;
import net.jcip.annotations.NotThreadSafe;

@NotThreadSafe
public class Wiring {

	private static final Object defaultId = new Object();

	@GuardedBy("registries")
	private static final Map<Object, Wiring> registries = new HashMap<Object, Wiring>();

	public static Wiring get(Object id) {
		if (id == null) {
			throw new NullPointerException("Can't use null as key");
		} else {
			synchronized (registries) {
				if (!registries.containsKey(id)) {
					registries.put(id, new Wiring(id));
				}
				return registries.get(id);
			}
		}
	}

	public static Wiring getDefault() {
		return get(defaultId);
	}

	private final Map<Class<?>, Map<Object, Object>> registry = new HashMap<Class<?>, Map<Object,Object>>();
	private final Object id;

	private Wiring(Object id) {
		this.id = id;
	}

	public Object getId() {
		return id;
	}

	public <T> T getWiring(Class<T> type) throws WiringException {
		return getWiring(type, defaultId);
	}

	// Unchecked type casts in this method are OK since wire functions enforce type safety
	@SuppressWarnings("unchecked")
	public <T> T getWiring(Class<T> type, Object id) throws WiringException {
		T result = null;
		if (registry.containsKey(type)) {
			Object o = registry.get(type).get(id);
			if (o != null) {
				if (o instanceof Class) {
					result = instantiate((Class<? extends T>) o);
				} else if (o instanceof WiringFactory) {
					result = ((WiringFactory<T>) o).produce();
				} else {
					result = (T) o;
				}
			}
		}
		if (result == null) {
			throw new WiringException(String.format("No wiring found for %s", type));
		}
		verifyResult(result);
		return result;
	}

	protected <T> boolean verifyResult(T result) {
		return true;
	}

	private <T> T instantiate(Class<? extends T> o) {
		T result = null;
		try {
			@SuppressWarnings("unchecked")
			Constructor<? extends T> ctor = (Constructor<? extends T>) getWiredConstructor(o);
			if (ctor == null) {
				result = o.newInstance();
			} else {
				result = instantiateWired(ctor);
			}	
		} catch (Exception e) {
			onGetWiringFailure(o, e);
		}
		return result;
	}

	private Constructor<?> getWiredConstructor(Class<?> type) {
		for (Constructor<?> ctor : type.getConstructors()) {
			if (ctor.getParameterTypes().length == 0) {
				continue;
			}

			boolean allWired = true;
			for (Annotation[] annotations : ctor.getParameterAnnotations()) {
				boolean parameterWired = false;
				for (Annotation annotation : annotations) {
					if (Wired.class.equals(annotation.annotationType())) {
						parameterWired = true;
						break;
					}
				}
				if (!parameterWired) {
					allWired = false;
					break;
				}
			}

			if (allWired) {
				return ctor;
			}
		}
		return null;
	}

	private <T> T instantiateWired(Constructor<T> ctor) throws WiringException {
		Object[] ctorParams = new Object[ctor.getParameterTypes().length];
		int paramIdx = 0;

		for (Annotation[] annotations : ctor.getParameterAnnotations()) {
			for (Annotation annotation : annotations) {
				if (Wired.class.equals(annotation.annotationType())) {
					Wired wiredAnnotation = (Wired) annotation;
					String id = wiredAnnotation.value();
					ctorParams[paramIdx] = getWiring(ctor.getParameterTypes()[paramIdx], "".equals(id) ? defaultId : id);
				}
			}
			paramIdx++;
		}

		try {
			return ctor.newInstance(ctorParams);
		} catch (InstantiationException | IllegalAccessException
				| IllegalArgumentException | InvocationTargetException e) {
			throw new WiringException(e);
		}
	}

	public <T> void wire(Class<T> type, Class<? extends T> impl) {
		wire(type, defaultId, impl);
	}

	public <T> void wire(Class<T> type, Object id, Class<? extends T> impl) {
		verifyWiredImpl(impl);
		wireGeneric(type, id, impl);
	}

	public <T> void wire(Class<T> type, T singleton) {
		wire(type, defaultId, singleton);
	}

	public <T> void wire(Class<T> type, Object id, T singleton) {
		wireGeneric(type, id, singleton);	
	}

	public <T> void wire(Class<T> type, WiringFactory<? extends T> implFactory) {
		wire(type, defaultId, implFactory);
	}

	public <T> void wire(Class<T> type, Object id, WiringFactory<? extends T> implFactory) {
		wireGeneric(type, id, implFactory);
	}

	public void clear() {
		registry.clear();
	}

	public void unwire(Class<?> type) {
		unwire(type, defaultId);
	}

	public void unwire(Class<?> type, Object id) {
		if (registry.containsKey(type)) {
			registry.get(type).remove(id);
		}
	}

	private <T> void verifyWiredImpl(Class<? extends T> impl) {
		boolean hasNoArgsConstructor = false;
		boolean hasWiredConstructor = false;

		for (Constructor<?> c : impl.getConstructors()) {
			if (c.getParameterTypes().length == 0) {
				hasNoArgsConstructor = true;
				break;
			}

			hasWiredConstructor = getWiredConstructor(impl) != null;
		}

		if (!hasNoArgsConstructor && !hasWiredConstructor) {
			throw new WiringException("Class has no no-args or wired constructor");
		}
	}

	protected <T> void onVerifyImplFailure(Class<? extends T> implType) {
		// Default behaviour is to just ignore failures
	}

	protected <T> void onGetWiringFailure(Class<? extends T> implType, Throwable cause) {
		// Default behaviour is to just ignore failures
	}

	private void wireGeneric(Class<?> type, Object id, Object o) {
		if (!registry.containsKey(type)) {
			registry.put(type, new HashMap<Object, Object>());
		}
		registry.get(type).put(id, o);
	}
}
