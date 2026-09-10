import { request } from '@/api/http'
import type { BackupSummaryVO, BackupVO } from '@/types'

/** 导出当前用户的全部学习数据，关联字段是业务键（slug / qKey）。 */
export function exportBackup(): Promise<BackupVO> {
  return request<BackupVO>({
    url: '/backup/export',
    method: 'get',
  })
}

/** 从导出的 JSON 恢复。按业务键幂等，相同文件重复导入结果一致。 */
export function importBackup(backup: BackupVO): Promise<BackupSummaryVO> {
  return request<BackupSummaryVO>({
    url: '/backup/import',
    method: 'post',
    data: backup,
  })
}
