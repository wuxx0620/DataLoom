/**
 * 稳定 JSON 序列化（排序 object key），用于结构快照对比。
 */
export function stableJson(obj) {
  return JSON.stringify(obj, (_, value) => {
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      return Object.keys(value)
        .sort()
        .reduce((acc, key) => {
          acc[key] = value[key]
          return acc
        }, {})
    }
    return value
  })
}
