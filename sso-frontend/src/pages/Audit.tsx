import { ScrollText } from "lucide-react";
import { useApiData } from "../useApiData";
import { PageHeader } from "@/components/PageHeader";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";

interface AuditEvent {
  id: number;
  occurredAt: string;
  principal: string | null;
  type: string;
  success: boolean;
  detail: string | null;
}

export default function Audit() {
  const { data, error } = useApiData<AuditEvent[]>("/api/admin/audit");

  return (
    <>
      <PageHeader
        title="Audit Log"
        description={data ? `Showing the latest ${data.length} security events.` : "Recent security events."}
      />

      <DataList
        data={data}
        error={error}
        isEmpty={(events) => events.length === 0}
        empty={<EmptyState icon={<ScrollText className="size-8" />} title="No audit events yet" />}
      >
        {(events) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Time</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Principal</TableHead>
                <TableHead>Result</TableHead>
                <TableHead>Detail</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {events.map((e) => (
                <TableRow key={e.id}>
                  <TableCell className="whitespace-nowrap text-muted-foreground">{new Date(e.occurredAt).toLocaleString()}</TableCell>
                  <TableCell className="font-medium">{e.type}</TableCell>
                  <TableCell>{e.principal ?? <span className="text-muted-foreground">—</span>}</TableCell>
                  <TableCell><Badge variant={e.success ? "success" : "destructive"}>{e.success ? "ok" : "fail"}</Badge></TableCell>
                  <TableCell className="max-w-md truncate font-mono text-xs text-muted-foreground" title={e.detail ?? undefined}>{e.detail ?? "—"}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>
    </>
  );
}
