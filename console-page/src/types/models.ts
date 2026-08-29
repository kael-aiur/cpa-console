export interface AvailableModel {
  name: string
  tags: string[]
}

export interface AvailableModelListResponse {
  models: AvailableModel[]
  total: number
  tags: string[]
}
