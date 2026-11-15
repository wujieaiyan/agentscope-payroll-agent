export async function downloadDocument(seriesId, documentId, filename) {
  const response = await fetch(`/api/kb/series/${seriesId}/documents/${documentId}/download`, {
    credentials: 'same-origin'
  })
  if (!response.ok) {
    throw new Error('下载失败')
  }
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename || 'document'
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}
