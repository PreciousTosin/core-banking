{{/* Common helper template for labels and image names */}}
{{- define "mcb.helper.labels" -}}
app.kubernetes.io/name: modern-core-banking-poc
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion }}
{{- end -}}

{{- define "mcb.image.apiEdge" -}}
{{ .Values.image.apiEdge.repository }}:{{ .Values.image.apiEdge.tag }}
{{- end -}}

{{- define "mcb.image.fundsCore" -}}
{{ .Values.image.fundsCore.repository }}:{{ .Values.image.fundsCore.tag }}
{{- end -}}
