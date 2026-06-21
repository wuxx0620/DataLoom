import { SPREADSHEET_ENGINES } from '@/adapters/spreadsheet/types'
import { LuckysheetAdapter } from '@/adapters/luckysheet/LuckysheetAdapter'

/**
 * 创建表格引擎适配器。Wave 3 将在此扩展 UniverAdapter。
 * @param {string} engine
 * @param {import('@/adapters/spreadsheet/types').SpreadsheetAdapterOptions} options
 */
export function createSpreadsheetAdapter(engine, options = {}) {
  switch (engine) {
    case SPREADSHEET_ENGINES.LUCKYSHEET:
      return new LuckysheetAdapter(options)
    case SPREADSHEET_ENGINES.UNIVER:
      throw new Error('UniverAdapter 尚未实现，请在 Wave 3 启用')
    default:
      throw new Error(`未知的表格引擎: ${engine}`)
  }
}

export { SPREADSHEET_ENGINES, SPREADSHEET_CONTAINER_ID } from '@/adapters/spreadsheet/types'
export { LuckysheetAdapter } from '@/adapters/luckysheet/LuckysheetAdapter'
