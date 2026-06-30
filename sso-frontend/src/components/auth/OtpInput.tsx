import { forwardRef } from "react";
import type { InputHTMLAttributes } from "react";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

/** 6-digit one-time-code input, styled consistently for every factor screen. */
export const OtpInput = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  ({ className, ...props }, ref) => (
    <Input
      ref={ref}
      inputMode="numeric"
      pattern="\d{6}"
      maxLength={6}
      placeholder="123456"
      autoFocus
      required
      className={cn("text-center text-2xl tracking-[0.5em] font-mono", className)}
      {...props}
    />
  ),
);
OtpInput.displayName = "OtpInput";
