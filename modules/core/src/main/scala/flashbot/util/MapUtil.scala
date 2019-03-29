package flashbot.util

object MapUtil {

  def getOrCompute[K, V](map: java.util.HashMap[K, V], key: K, default: => V): V = {
    var value = map.get(key)
    if (value == null) {
      value = default
      map.put(key, value)
    }
    value
  }

  def getOrCompute[K, V](map: java.util.HashMap[K, java.util.HashMap[K, V]],
                         key1: K, key2: K, default: => V): V = {
    val subMap = getOrCompute[K, java.util.HashMap[K, V]](map, key1, new java.util.HashMap[K, V]())
    getOrCompute[K, V](subMap, key2, default)
  }

  def hashMap2d[K, V]: java.util.HashMap[K, java.util.HashMap[K, V]] =
    new java.util.HashMap[K, java.util.HashMap[K, V]]()

}
