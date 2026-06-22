/**
 * Luckysheet 公式链：用于依赖追踪与自动重算。
 * 从 celldata 加载时必须重建，否则修改引用单元格不会触发公式更新。
 */

function hasFormula(value) {
  if (!value || typeof value !== 'object') return false
  const formula = value.f
  return typeof formula === 'string' && formula.trim().length > 0
}

/**
 * 从 celldata 构建 calcChain 条目。
 * @param {Array<{r:number,c:number,v?:object}>} celldata
 * @param {string|number} sheetIndex Luckysheet sheet index（如 "0"）
 */
export function buildCalcChainFromCelldata(celldata, sheetIndex) {
  if (!Array.isArray(celldata)) return []

  const index = String(sheetIndex)
  const chain = []
  const seen = new Set()

  celldata.forEach((cell) => {
    if (!cell || !hasFormula(cell.v)) return
    const key = `${cell.r}_${cell.c}`
    if (seen.has(key)) return
    seen.add(key)

    chain.push({
      r: cell.r,
      c: cell.c,
      index,
      color: 'w',
      parent: null,
      chidren: {},
      times: 0
    })
  })

  return chain
}

/**
 * 若 sheet 未带 calcChain，则根据 celldata / data 矩阵补全。
 */
export function resolveSheetCalcChain(sheet, sheetIndex) {
  if (Array.isArray(sheet?.calcChain) && sheet.calcChain.length > 0) {
    return sheet.calcChain
  }

  const fromCelldata = buildCalcChainFromCelldata(sheet?.celldata, sheetIndex)
  if (fromCelldata.length > 0) return fromCelldata

  // 全量保存后可能只有 data 矩阵，从中反查公式单元格
  const fromData = []
  const data = sheet?.data
  if (Array.isArray(data)) {
    data.forEach((row, r) => {
      if (!Array.isArray(row)) return
      row.forEach((cell, c) => {
        if (hasFormula(cell)) {
          fromData.push({
            r,
            c,
            index: String(sheetIndex),
            color: 'w',
            parent: null,
            chidren: {},
            times: 0
          })
        }
      })
    })
  }

  return fromData
}

/**
 * 将 calcChain 写回 Luckysheet 内存中的 sheet 对象。
 */
export function ensureSheetCalcChain(sheet) {
  if (!sheet) return []
  const chain = resolveSheetCalcChain(sheet, sheet.index)
  sheet.calcChain = chain
  return chain
}

/**
 * 遍历工作簿全部 sheet，补全 calcChain。
 */
export function ensureWorkbookCalcChains(luckysheet) {
  const files = luckysheet?.getluckysheetfile?.() || []
  files.forEach((sheet) => ensureSheetCalcChain(sheet))
  return files
}

/**
 * 公式重算后，将公式单元格标记为脏数据以便增量保存。
 */
export function collectFormulaCellUpdates(luckysheet, getSheetIdMap, onCellDirty) {
  if (!luckysheet || typeof onCellDirty !== 'function') return

  const sheetIdMap = getSheetIdMap?.() || {}
  const files = luckysheet.getluckysheetfile?.() || []

  files.forEach((sheet) => {
    const dbSheetId = sheetIdMap[sheet.index]
    if (!dbSheetId) return

    const data = Array.isArray(sheet.data) && sheet.data.length > 0
      ? sheet.data
      : null

    ;(sheet.calcChain || []).forEach(({ r, c }) => {
      const cell = data?.[r]?.[c]
      if (!cell || !hasFormula(cell)) return
      onCellDirty({
        sheetId: dbSheetId,
        r,
        c,
        v: cell,
        sheetIndex: sheet.index
      })
    })
  })
}

export function refreshLuckysheetFormulas(luckysheet, callback) {
  if (!luckysheet?.refreshFormula) {
    callback?.()
    return
  }
  luckysheet.refreshFormula(() => callback?.())
}
