import 'package:flutter/material.dart';

import 'i18n.dart';

/// Header and hint colors from the Telegram theme.
class SectionColors extends ThemeExtension<SectionColors> {
  const SectionColors({this.header, this.hint});

  final Color? header;
  final Color? hint;

  @override
  SectionColors copyWith({Color? header, Color? hint}) =>
      SectionColors(header: header ?? this.header, hint: hint ?? this.hint);

  @override
  SectionColors lerp(SectionColors? other, double t) => this;
}

Color hintColor(BuildContext context) =>
    Theme.of(context).extension<SectionColors>()?.hint ?? Theme.of(context).colorScheme.onSurfaceVariant;

/// A Telegram-style settings group: caption, rounded card, optional footer.
class Section extends StatelessWidget {
  const Section({super.key, required this.title, required this.children, this.footer});

  final String title;
  final List<Widget> children;
  final String? footer;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).extension<SectionColors>();
    final scheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(top: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 6),
            child: Text(
              title.toUpperCase(),
              style: TextStyle(
                fontSize: 13,
                letterSpacing: 0.4,
                color: colors?.header ?? scheme.primary,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          Material(
            color: scheme.surface,
            borderRadius: BorderRadius.circular(12),
            clipBehavior: Clip.antiAlias,
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children),
          ),
          if (footer != null)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 6, 16, 0),
              child: Text(footer!, style: TextStyle(fontSize: 13, color: hintColor(context))),
            ),
        ],
      ),
    );
  }
}

/// A highlighted message, optionally with a button.
class Note extends StatelessWidget {
  const Note(this.text, {super.key, this.action, this.onAction, this.warning = false});

  final String text;
  final String? action;
  final VoidCallback? onAction;
  final bool warning;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final bg = warning ? scheme.errorContainer : scheme.primaryContainer;
    final fg = warning ? scheme.onErrorContainer : scheme.onPrimaryContainer;
    return Container(
      margin: const EdgeInsets.only(top: 12),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(12)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(text, style: TextStyle(color: fg)),
          if (action != null)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: FilledButton.tonal(onPressed: onAction, child: Text(action!)),
            ),
        ],
      ),
    );
  }
}

/// A list of short strings as chips, with a field to add more (Enter or comma adds).
class TagsField extends StatefulWidget {
  const TagsField({
    super.key,
    required this.label,
    required this.values,
    required this.onChanged,
    this.hint,
    this.splitOnComma = true,
  });

  final String label;
  final String? hint;
  final List<String> values;
  final ValueChanged<List<String>> onChanged;

  /// Words and phrases split on commas; chat names may contain them.
  final bool splitOnComma;

  @override
  State<TagsField> createState() => _TagsFieldState();
}

class _TagsFieldState extends State<TagsField> {
  final _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _add(String text) {
    final parts = (widget.splitOnComma ? text.split(',') : [text]).map((s) => s.trim()).where((s) => s.isNotEmpty);
    final updated = [...widget.values];
    for (final p in parts) {
      if (!updated.any((v) => v.toLowerCase() == p.toLowerCase())) updated.add(p);
    }
    _controller.clear();
    if (updated.length != widget.values.length) widget.onChanged(updated);
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (widget.values.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  for (final v in widget.values)
                    InputChip(
                      label: Text(v),
                      onDeleted: () => widget.onChanged([...widget.values]..remove(v)),
                      visualDensity: VisualDensity.compact,
                    ),
                ],
              ),
            ),
          TextField(
            controller: _controller,
            decoration: InputDecoration(
              labelText: widget.label,
              hintText: widget.hint,
              isDense: true,
              suffixIcon: IconButton(
                icon: const Icon(Icons.add),
                tooltip: tr('Add', 'Добавить'),
                onPressed: () => _add(_controller.text),
              ),
            ),
            textInputAction: TextInputAction.done,
            onSubmitted: _add,
            onChanged: (v) {
              if (widget.splitOnComma && v.contains(',')) _add(v);
            },
          ),
        ],
      ),
    );
  }
}
