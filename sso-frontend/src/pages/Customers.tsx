import { Building, Pause, Pencil, Play, Plus, Trash2 } from "lucide-react";
import {
  createCustomer, updateCustomer,
  type Customer, type CustomerStatus,
} from "@/customers";
import { usePaginated } from "@/usePaginated";
import { errorMessage } from "@/api";
import { Pagination } from "@/components/Pagination";
import { PageHeader } from "@/components/PageHeader";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DataList, EmptyState } from "@/components/states";
import { useDeleteConfirm } from "@/hooks/useDeleteConfirm";
import { useEditorForm } from "@/hooks/useEditorForm";

interface Editor {
  id: string | null;
  slug: string;
  name: string;
  status: CustomerStatus;
}

const blankEditor: Editor = { id: null, slug: "", name: "", status: "ACTIVE" };

/**
 * Platform super-admin registry of customers (고객사) — the top tenancy tier. Each customer owns one or more
 * organizations (branches). The nav item is super-admin-gated and the API enforces platform permissions
 * server-side, so a tenant admin can reach neither. Branches are managed from the Organizations page.
 */
export default function Customers() {
  const confirmDelete = useDeleteConfirm();
  const { items: customers, total, page, setPage, size, error: listError, reload } =
    usePaginated<Customer>("/api/admin/customers");

  const {
    editor, set, setEditor, open, setOpen, error, openCreate, openEdit, save,
  } = useEditorForm<Editor>({
    blank: blankEditor,
    toRequest: (e) => ({ slug: e.slug, name: e.name, status: e.status }),
    create: (body) => createCustomer({ slug: (body as Editor).slug, name: (body as Editor).name }),
    update: (id, body) => updateCustomer(id, { name: (body as Editor).name, status: (body as Editor).status }),
    onSaved: reload,
  });

  async function toggleStatus(customer: Customer) {
    const next: CustomerStatus = customer.status === "ACTIVE" ? "SUSPENDED" : "ACTIVE";
    try {
      await updateCustomer(customer.id, { name: customer.name, status: next });
      reload();
    } catch (e) {
      window.alert(errorMessage(e)); // rare; step-up is handled by the api client
    }
  }

  async function remove(customer: Customer) {
    await confirmDelete({
      title: "Delete customer?",
      description: `Customer "${customer.name}" (${customer.slug}) will be permanently removed. `
        + "Its organizations are not deleted; reassign or remove them first.",
      path: `/api/admin/customers/${customer.id}`,
      onDeleted: reload,
    });
  }

  return (
    <>
      <PageHeader
        title="Customers"
        description="Top-level customers served by this identity provider. Each customer owns one or more organizations (branches)."
        actions={<Button onClick={openCreate}><Plus /> New customer</Button>}
      />

      <DataList
        data={customers}
        error={listError}
        isEmpty={(items) => items.length === 0}
        empty={<EmptyState icon={<Building className="size-8" />} title="No customers yet"
                           hint="Create a customer to group its organizations under one account." />}
      >
        {(items) => (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Customer</TableHead>
                <TableHead>Identifier</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Created</TableHead>
                <TableHead className="w-0" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((customer) => (
                <TableRow key={customer.id}>
                  <TableCell className="font-medium">
                    <span className="inline-flex items-center gap-2">
                      <Building className="size-4 text-muted-foreground" /> {customer.name}
                    </span>
                  </TableCell>
                  <TableCell className="font-mono text-xs text-muted-foreground">{customer.slug}</TableCell>
                  <TableCell>
                    <Badge variant={customer.status === "ACTIVE" ? "success" : "destructive"}>
                      {customer.status === "ACTIVE" ? "Active" : "Suspended"}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {new Date(customer.createdAt).toLocaleDateString()}
                  </TableCell>
                  <TableCell className="text-right">
                    <div className="flex justify-end gap-1">
                      <Button variant="ghost" size="icon" title="Rename" onClick={() =>
                        openEdit({ id: customer.id, slug: customer.slug, name: customer.name, status: customer.status })}>
                        <Pencil />
                      </Button>
                      <Button variant="ghost" size="icon"
                              title={customer.status === "ACTIVE" ? "Suspend" : "Activate"}
                              onClick={() => toggleStatus(customer)}>
                        {customer.status === "ACTIVE" ? <Pause /> : <Play />}
                      </Button>
                      <Button variant="ghost" size="icon" className="text-muted-foreground hover:text-destructive"
                              title="Delete" onClick={() => remove(customer)}>
                        <Trash2 />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DataList>
      <Pagination page={page} size={size} total={total} onPage={setPage} />

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editor.id ? `Rename: ${editor.slug}` : "Create customer"}</DialogTitle>
            <DialogDescription>
              {editor.id
                ? "The identifier is permanent; only the display name can change."
                : "The identifier is the customer's subdomain label — lowercase, no spaces."}
            </DialogDescription>
          </DialogHeader>

          {error && <Alert variant="destructive"><AlertDescription>{error}</AlertDescription></Alert>}

          <form onSubmit={save} className="space-y-4">
            {!editor.id && (
              <div className="space-y-2">
                <Label htmlFor="customer-slug">Identifier</Label>
                <Input id="customer-slug" value={editor.slug} required autoFocus
                       autoCapitalize="none" autoCorrect="off" spellCheck={false}
                       placeholder="acme" onChange={(e) => set({ slug: e.target.value })} />
              </div>
            )}
            <div className="space-y-2">
              <Label htmlFor="customer-name">Display name</Label>
              <Input id="customer-name" value={editor.name} required autoFocus={!!editor.id}
                     placeholder="Acme, Inc." onChange={(e) => set({ name: e.target.value })} />
            </div>

            <DialogFooter>
              <Button type="button" variant="outline"
                      onClick={() => { setEditor(blankEditor); setOpen(false); }}>Cancel</Button>
              <Button type="submit">{editor.id ? "Save changes" : "Create customer"}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  );
}
