import {
  Settings as SettingsIcon,
} from 'lucide-react'
import { Card, CardHeader } from '@/components/Card'

export function Settings() {
  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-3xl font-bold flex items-center gap-3">
          <SettingsIcon className="h-8 w-8" />
          Settings
        </h1>
        <p className="text-muted-foreground mt-1">
          Configure Claude Flow behavior and integrations
        </p>
      </div>

      {/* General Settings */}
      <Card>
        <CardHeader
          title="General Settings"
          description="General configuration options"
        />
        <div className="text-center py-12 text-muted-foreground">
          <SettingsIcon className="h-12 w-12 mx-auto mb-3 opacity-50" />
          <p>No general settings available yet</p>
          <p className="text-sm">More settings will be added in future updates</p>
        </div>
      </Card>

      {/* Info Card */}
      <Card>
        <CardHeader
          title="Project Aliases"
          description="Project aliases have been moved to the Projects page for better organization."
        />
        <div className="text-center py-8 text-muted-foreground">
          <p className="text-sm">
            Go to <a href="/projects" className="text-primary hover:underline font-medium">Projects</a> page to manage project aliases.
          </p>
        </div>
      </Card>
    </div>
  )
}

export default Settings
