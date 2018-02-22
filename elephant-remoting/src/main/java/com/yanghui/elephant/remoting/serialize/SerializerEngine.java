package com.yanghui.elephant.remoting.serialize;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
/**
 * 
 * @author --小灰灰--
 *
 */
public class SerializerEngine {
	
	private static final Map<SerializeType, ISerializer> serializerMap = new ConcurrentHashMap<SerializeType, ISerializer>();

	static {
		serializerMap.put(SerializeType.HESSIAN,new HessianSerializer());
		serializerMap.put(SerializeType.JSON,new JsonSerializer());
		serializerMap.put(SerializeType.CUSTOM, new CostomSerializer());
	}
	
	public static <T> byte[] serialize(T obj, byte code) {
        SerializeType serialize = SerializeType.valueOf(code);
        if (serialize == null) {
            throw new RuntimeException("serialize is null");
        }
        ISerializer serializer = serializerMap.get(serialize);
        if (serializer == null) {
            throw new RuntimeException("serialize error");
        }
        return serializer.serializer(obj);
    }

    public static <T> T deserialize(byte[] data, Class<T> clazz, byte code) {
        SerializeType serialize = SerializeType.valueOf(code);
        if (serialize == null) {
            throw new RuntimeException("serialize is null");
        }
        ISerializer serializer = serializerMap.get(serialize);
        if (serializer == null) {
            throw new RuntimeException("serialize error");
        }
        return serializer.deserializer(data, clazz);
    }
}
