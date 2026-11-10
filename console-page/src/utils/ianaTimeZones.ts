/**
 * Browser-provided IANA ZoneId catalogue used by monitor scheduling forms.
 * The backend remains authoritative for validation; this list prevents arbitrary
 * strings from being entered in the UI while keeping the full browser tzdb set.
 */
export function getSupportedIanaTimeZones(): string[] {
  const intl = Intl as typeof Intl & {
    supportedValuesOf?: (key: 'timeZone') => string[]
  }
  const zones = intl.supportedValuesOf?.('timeZone') ?? [
    'Africa/Cairo', 'Africa/Johannesburg', 'America/Chicago', 'America/Denver',
    'America/Los_Angeles', 'America/New_York', 'America/Phoenix', 'Asia/Shanghai',
    'Asia/Tokyo', 'Australia/Sydney', 'Europe/Berlin', 'Europe/London',
    'Europe/Paris', 'Pacific/Auckland',
  ]
  return Array.from(new Set(['UTC', ...zones])).sort((a, b) => a.localeCompare(b))
}

export const SUPPORTED_IANA_TIME_ZONES = getSupportedIanaTimeZones()
