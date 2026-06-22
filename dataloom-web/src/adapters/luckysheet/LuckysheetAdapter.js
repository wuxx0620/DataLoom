import { SPREADSHEET_CONTAINER_ID } from '@/adapters/spreadsheet/types'
import { stableJson } from '@/adapters/spreadsheet/stableJson'
import { buildLuckysheetCreatePayload } from '@/adapters/luckysheet/chartHelpers'
import {
  collectFormulaCellUpdates,
  ensureWorkbookCalcChains,
  refreshLuckysheetFormulas
} from '@/adapters/luckysheet/formulaHelpers'
import { serializeLuckysheetWorkbook } from '@/adapters/luckysheet/serializeWorkbook'

export class LuckysheetAdapter {
  /**
   * @param {import('@/adapters/spreadsheet/types').SpreadsheetAdapterOptions} options
   */
  constructor(options = {}) {
    this.containerId = options.containerId || SPREADSHEET_CONTAINER_ID
    this.getSheetIdMap = options.getSheetIdMap || (() => ({}))
    this.onCellDirty = options.onCellDirty
    this.onWorkbookDirty = options.onWorkbookDirty
    this.onStructureDirty = options.onStructureDirty

    this.structureSnapshot = null
    this.keydownHandler = null
    this.toolbarClickHandler = null
    this.formulaRefreshTimer = null
    this.recalculatingFormulas = false
  }

  get luckysheet() {
    return window.luckysheet
  }

  isReady() {
    return Boolean(this.luckysheet)
  }

  init(sheets) {
    if (!this.luckysheet) {
      throw new Error('Luckysheet 资源未加载，请检查 index.html 中的脚本配置')
    }

    try {
      this.luckysheet.destroy?.()
    } catch {
      // Ignore stale instances from previous route visits.
    }

    const payload = buildLuckysheetCreatePayload(sheets)
    this.luckysheet.create({
      container: this.containerId,
      lang: 'zh',
      showtoolbar: true,
      showinfobar: false,
      showstatisticBar: true,
      allowUpdate: true,
      forceCalculation: true,
      plugins: ['chart'],
      pluginsUrl: window.location.origin,
      data: payload,
      hook: {
        workbookCreateAfter: () => {
          ensureWorkbookCalcChains(this.luckysheet)
          this.refreshFormulas({ markDirty: false })
        },
        updated: () => {
          this.onWorkbookDirty?.()
          this.markCurrentSelectionDirty()
        },
        cellUpdated: (r, c, _oldValue, newValue) => {
          if (!this.recalculatingFormulas) {
            this.markCellDirty(r, c, newValue)
          }
          this.scheduleFormulaRefresh()
        },
        imageDeleteAfter: (imageItem) => this.handleImageDelete(imageItem)
      }
    })

    ensureWorkbookCalcChains(this.luckysheet)
    this.refreshFormulas({ markDirty: false })

    this.captureStructureSnapshot()
    this.bindDomHandlers()
  }

  destroy() {
    this.unbindDomHandlers()
    if (this.formulaRefreshTimer) {
      clearTimeout(this.formulaRefreshTimer)
      this.formulaRefreshTimer = null
    }

    try {
      this.luckysheet?.destroy?.()
    } catch (error) {
      console.warn('Luckysheet destroy error:', error)
    }

    this.structureSnapshot = null
  }

  scheduleFormulaRefresh() {
    if (this.formulaRefreshTimer) {
      clearTimeout(this.formulaRefreshTimer)
    }
    this.formulaRefreshTimer = window.setTimeout(() => {
      this.formulaRefreshTimer = null
      this.refreshFormulas({ markDirty: true })
    }, 30)
  }

  refreshFormulas({ markDirty = true } = {}) {
    if (!this.luckysheet) return

    ensureWorkbookCalcChains(this.luckysheet)
    this.recalculatingFormulas = true

    refreshLuckysheetFormulas(this.luckysheet, () => {
      if (markDirty) {
        collectFormulaCellUpdates(this.luckysheet, this.getSheetIdMap, this.onCellDirty)
        this.onWorkbookDirty?.()
      }
      this.recalculatingFormulas = false
    })
  }

  bindDomHandlers() {
    this.unbindDomHandlers()

    this.keydownHandler = (event) => {
      if (event.key === 'Delete' || event.key === 'Backspace') {
        window.setTimeout(() => this.markCurrentSelectionDirty(), 50)
      }
    }

    this.toolbarClickHandler = () => {
      window.setTimeout(() => this.markCurrentSelectionDirty(), 100)
    }

    document.addEventListener('keydown', this.keydownHandler, true)
    document.getElementById(this.containerId)?.addEventListener('click', this.toolbarClickHandler, true)
  }

  unbindDomHandlers() {
    if (this.keydownHandler) {
      document.removeEventListener('keydown', this.keydownHandler, true)
      this.keydownHandler = null
    }

    const container = document.getElementById(this.containerId)
    if (container && this.toolbarClickHandler) {
      container.removeEventListener('click', this.toolbarClickHandler, true)
    }
    this.toolbarClickHandler = null
  }

  injectSheetData(index, celldata) {
    if (!this.luckysheet?.getluckysheetfile) return

    try {
      const files = this.luckysheet.getluckysheetfile()
      const target = files.find((file) => file.index === String(index))
      if (!target) return

      target.celldata = celldata
      if (target.data && this.luckysheet.buildGridData) {
        target.data = this.luckysheet.buildGridData(target)
      }
      if (this.luckysheet.getSheet?.()?.index === target.index) {
        this.luckysheet.refresh?.()
      }
    } catch (error) {
      console.warn('Inject sheet data failed:', error)
    }
  }

  markCellDirty(r, c, newValue) {
    const currentSheet = this.luckysheet?.getSheet?.()
    if (!currentSheet) return

    this.onWorkbookDirty?.()

    const sheetIdMap = this.getSheetIdMap()
    const dbSheetId = sheetIdMap[currentSheet.index]
    if (!dbSheetId) return

    const sheetData = this.luckysheet.getSheetData?.() || []
    const fullCell = newValue && typeof newValue === 'object' ? newValue : sheetData[r]?.[c] || null

    this.onCellDirty?.({
      sheetId: dbSheetId,
      r,
      c,
      v: fullCell,
      sheetIndex: currentSheet.index
    })
  }

  markCurrentSelectionDirty() {
    const ranges = this.luckysheet?.getRange?.()
    const currentSheet = this.luckysheet?.getSheet?.()
    if (!ranges?.length || !currentSheet) return

    this.onWorkbookDirty?.()

    const sheetIdMap = this.getSheetIdMap()
    const dbSheetId = sheetIdMap[currentSheet.index]
    if (!dbSheetId) return

    const sheetData = this.luckysheet.getSheetData?.() || []
    ranges.forEach((range) => {
      if (!range.row || !range.column) return

      for (let r = range.row[0]; r <= range.row[1]; r += 1) {
        for (let c = range.column[0]; c <= range.column[1]; c += 1) {
          this.onCellDirty?.({
            sheetId: dbSheetId,
            r,
            c,
            v: sheetData[r]?.[c] || null,
            sheetIndex: currentSheet.index
          })
        }
      }
    })
  }

  handleImageDelete(imageItem) {
    this.onWorkbookDirty?.()
    this.onStructureDirty?.()

    if (!this.luckysheet?.getluckysheetfile || !imageItem) return

    const files = this.luckysheet.getluckysheetfile()
    const targetId = imageItem.id || imageItem.imgId || imageItem.imageId
    const targetSrc = imageItem.src

    files.forEach((sheet) => {
      if (!sheet.images) return

      if (targetId && sheet.images[targetId]) {
        delete sheet.images[targetId]
      }

      if (targetSrc) {
        Object.keys(sheet.images).forEach((key) => {
          if (sheet.images[key]?.src === targetSrc) {
            delete sheet.images[key]
          }
        })
      }

      Object.keys(sheet.images).forEach((key) => {
        const img = sheet.images[key]
        if (img
            && Math.abs((img.left || 0) - (imageItem.left || 0)) < 5
            && Math.abs((img.top || 0) - (imageItem.top || 0)) < 5
            && Math.abs((img.width || 0) - (imageItem.width || 0)) < 5
            && Math.abs((img.height || 0) - (imageItem.height || 0)) < 5) {
          delete sheet.images[key]
        }
      })
    })
  }

  captureStructureSnapshot() {
    const files = this.luckysheet?.getluckysheetfile?.() || []
    this.structureSnapshot = files.map((sheet) => ({
      name: sheet.name,
      index: sheet.index,
      status: sheet.status,
      order: sheet.order,
      configSig: stableJson(sheet.config || {}),
      imagesSig: stableJson(sheet.images || {}),
      chartSig: stableJson(sheet.chart || []),
      hyperlinkSig: stableJson(sheet.hyperlink || {}),
      conditionFormatSig: stableJson(sheet.luckysheet_conditionformat_save || [])
    }))
    return this.structureSnapshot
  }

  detectStructuralChange() {
    const files = this.luckysheet?.getluckysheetfile?.() || []
    const snapshot = this.structureSnapshot

    if (!snapshot || files.length !== snapshot.length) {
      return {
        changed: true,
        reason: `Sheet 数量变化: ${snapshot?.length ?? 0} → ${files.length}`
      }
    }

    for (let i = 0; i < files.length; i += 1) {
      const current = files[i]
      const snap = snapshot[i]
      if (!snap) return { changed: true, reason: `Sheet[${i}] 为新增` }

      if (current.name !== snap.name) {
        return { changed: true, reason: `Sheet[${i}] 名称变更: "${snap.name}" → "${current.name}"` }
      }
      if (current.status !== snap.status) return { changed: true, reason: `Sheet[${i}] 激活状态变更` }
      if (current.order !== snap.order) return { changed: true, reason: `Sheet[${i}] 顺序变更` }
      if (stableJson(current.config || {}) !== snap.configSig) {
        return { changed: true, reason: `Sheet[${i}] config 变更（合并单元格/列宽/行高）` }
      }
      if (stableJson(current.images || {}) !== snap.imagesSig) {
        return { changed: true, reason: `Sheet[${i}] 图片变更` }
      }
      if (stableJson(current.chart || []) !== snap.chartSig) {
        return { changed: true, reason: `Sheet[${i}] 图表变更` }
      }
      if (stableJson(current.hyperlink || {}) !== snap.hyperlinkSig) {
        return { changed: true, reason: `Sheet[${i}] 超链接变更` }
      }
      if (stableJson(current.luckysheet_conditionformat_save || []) !== snap.conditionFormatSig) {
        return { changed: true, reason: `Sheet[${i}] 条件格式变更` }
      }
    }

    return { changed: false }
  }

  serializeWorkbook() {
    if (!this.luckysheet) return []
    return serializeLuckysheetWorkbook(this.luckysheet)
  }

  getSheetsForExport() {
    return this.luckysheet?.getAllSheets?.() || null
  }
}
