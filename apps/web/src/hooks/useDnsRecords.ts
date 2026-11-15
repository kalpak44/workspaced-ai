import { useEffect, useState } from "react";
import { type DnsRecord, type DnsRecordInput, listRecords, removeRecord, saveRecord } from "../api/domainsApi";

export { RECORD_TYPES, SIMPLE_TARGET } from "../api/domainsApi";
export type { DnsRecord, DnsRecordInput, RecordType } from "../api/domainsApi";

/** A record name a zone accepts: "@", or dot-separated labels, optionally led by "*". */
export const RECORD_NAME = /^(?:@|(?:\*\.)?[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9])?(?:\.[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9])?)*|\*)$/;

/** Says what is wrong with a record's content for its type, or null when it is fine. */
export function contentError(type: DnsRecordInput["type"], content: string): string | null {
  const value = content.trim();
  if (value === "") return "Enter a value.";
  switch (type) {
    case "A":
      return /^(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)$/.test(value) ? null : "Enter an IPv4 address, like 203.0.113.10.";
    case "AAAA":
      return /^[0-9a-f:]+$/i.test(value) && value.includes(":") ? null : "Enter an IPv6 address, like 2001:db8::1.";
    case "CNAME":
    case "MX":
      return /^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}\.?$/i.test(value) ? null : "Enter a hostname, like mail.example.com.";
    case "TXT":
      return value.length <= 2048 ? null : "Keep it to 2048 characters.";
  }
}

/** One domain's records, loaded on mount and replaced by whatever each change returns. */
export function useDnsRecords(domain: string) {
  const [records, setRecords] = useState<DnsRecord[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;
    listRecords(domain).then(
      (list) => {
        if (active) {
          setRecords(list);
          setLoading(false);
        }
      },
      () => active && setLoading(false),
    );
    return () => {
      active = false;
    };
  }, [domain]);

  return {
    records,
    loading,
    save: async (record: DnsRecordInput, id?: string) => setRecords(await saveRecord(domain, record, id)),
    remove: async (id: string) => setRecords(await removeRecord(domain, id)),
  };
}
