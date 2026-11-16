param([string]$BaseUrl = 'http://localhost:8081')
$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$script:ResearchDemoToken = $null

function Invoke-Hub([string]$Method, [string]$Path, $Body = $null, [string]$RequestKey = '') {
    $headers = @{}
    if ($script:ResearchDemoToken) { $headers.Authorization = 'Bearer ' + $script:ResearchDemoToken }
    if ($RequestKey) { $headers['Idempotency-Key'] = $RequestKey }
    $parameters = @{ Method = $Method; Uri = $BaseUrl + $Path; Headers = $headers; TimeoutSec = 15 }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json; charset=utf-8'
        $parameters.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20))
    }
    Invoke-RestMethod @parameters
}

function Wait-HubJob([string]$Id) {
    $deadline = [DateTime]::UtcNow.AddSeconds(150)
    do {
        $job = Invoke-Hub 'GET' ('/api/jobs/' + $Id)
        if ($job.status -eq 'SUCCEEDED') { return $job }
        if ($job.status -in @('FAILED', 'CANCELLED')) { throw ('Job failed. ' + $job.error) }
        Start-Sleep -Milliseconds 400
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Job polling deadline exceeded.'
}

$email = 'demo-' + [Guid]::NewGuid().ToString('N').Substring(0, 10) + '@example.test'
$session = Invoke-Hub 'POST' '/api/auth/register' @{email=$email; displayName='演示研究生'; password='Demo-password-2026'}
$script:ResearchDemoToken = $session.token
$workspace = Invoke-Hub 'POST' '/api/workspaces' @{name='科研协作演示组'}
$prefix = '/api/workspaces/' + $workspace.id

$content = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'reading-material.md') -Raw -Encoding UTF8
$import = Invoke-Hub 'POST' ($prefix + '/documents/text') @{title='组会材料整理的验证方法'; content=$content; visibility='GROUP'}
$null = Wait-HubJob $import.job.id
$note = Invoke-Hub 'PUT' ($prefix + '/documents/' + $import.document.id + '/notes/mine') @{
    readingStatus='DONE'; visibility='GROUP'; content='已阅读示例，计划准备公开论文并记录审核耗时。实验尚未开始。'; version=0
}

$today = [DateTime]::UtcNow.ToString('yyyy-MM-dd')
$meeting = Invoke-Hub 'POST' ($prefix + '/meetings') @{
    title='本周文献阅读组会'; meetingDate=$today; periodStart=[DateTime]::UtcNow.AddDays(-7).ToString('yyyy-MM-dd'); periodEnd=$today
}
$meetingPath = $prefix + '/meetings/' + $meeting.id
$draftJob = Invoke-Hub 'POST' ($meetingPath + '/draft-jobs') $null ('draft-' + [Guid]::NewGuid().ToString('N'))
$draft = Wait-HubJob $draftJob.id
$meeting = Invoke-Hub 'PUT' ($meetingPath + '/minutes') @{
    content="讨论了资料引用与效率评测方法。`n- [ ] 整理公开论文和对应的阅读记录`n待办：为下一次组会记录人工审核耗时"; version=0
}
$actionJob = Invoke-Hub 'POST' ($meetingPath + '/action-jobs') $null ('actions-' + [Guid]::NewGuid().ToString('N'))
$actions = Wait-HubJob $actionJob.id
if ($actions.result.items.Count -eq 0) { throw 'No action proposals were produced.' }
$tasks = Invoke-Hub 'POST' ($meetingPath + '/confirm-actions') @{
    proposalVersion=$meeting.minutesVersion
    items=@(@{index=0; title=$actions.result.items[0].title; assigneeId=$session.user.id; dueDate=[DateTime]::UtcNow.AddDays(7).ToString('yyyy-MM-dd')})
}

[PSCustomObject]@{
    Email = $email
    DemoPassword = 'Demo-password-2026'
    WorkspaceId = $workspace.id
    DocumentId = $import.document.id
    MeetingId = $meeting.id
    Mode = $draft.result.mode
    DraftPoints = $draft.result.points.Count
    ActionProposals = $actions.result.items.Count
    CreatedTasks = @($tasks).Count
    ApiDocs = $BaseUrl + '/docs'
} | Format-List
