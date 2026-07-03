import { apiGet, apiPost, apiPut, type Page } from "./api";

/** A reusable named IP zone (catalog entry) referenced by session policies. */
export interface NetworkZone {
  id: string;
  name: string;
  description: string | null;
  cidrs: string[];
}

/** The whole catalog (it is small — one page). */
export const listZones = (): Promise<NetworkZone[]> =>
  apiGet<Page<NetworkZone>>("/api/admin/network-zones?size=200").then((p) => p.items);

export const createZone = (body: unknown): Promise<NetworkZone> =>
  apiPost<NetworkZone>("/api/admin/network-zones", body);

export const updateZone = (id: string, body: unknown): Promise<NetworkZone> =>
  apiPut<NetworkZone>(`/api/admin/network-zones/${id}`, body);

/** Typeahead source for the session-policy zone picker (name-matched against the catalog). */
export const searchZones = (query: string): Promise<{ id: string; label: string }[]> =>
  listZones().then((zones) =>
    zones
      .filter((z) => z.name.toLowerCase().includes(query.toLowerCase()))
      .map((z) => ({ id: z.id, label: z.name })));
