export const SPREADSHEET_CONTAINER_ID = 'luckysheet-container'

export const SPREADSHEET_ENGINES = {
  LUCKYSHEET: 'luckysheet',
  UNIVER: 'univer'
}

/**
 * @typedef {Object} SpreadsheetAdapterOptions
 * @property {string} [containerId]
 * @property {() => Record<string, string|number>} getSheetIdMap
 * @property {(payload: { r: number, c: number, v: unknown, sheetIndex: string }) => void} [onCellDirty]
 * @property {() => void} [onWorkbookDirty]
 * @property {() => void} [onStructureDirty]
 */

/**
 * @typedef {Object} StructureSnapshotItem
 * @property {string} name
 * @property {string} index
 * @property {number} status
 * @property {number} order
 * @property {string} configSig
 * @property {string} imagesSig
 * @property {string} chartSig
 * @property {string} hyperlinkSig
 * @property {string} conditionFormatSig
 */

/**
 * @typedef {Object} StructuralChangeResult
 * @property {boolean} changed
 * @property {string} [reason]
 */
