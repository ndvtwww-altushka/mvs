package com.duckduckgram.duckerer.ui;

import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import app.exteraless.appearance.AppearanceConfig;

import com.duckduckgram.duckerer.config.DdgConfig;
import com.duckduckgram.duckerer.ink.InkAccent;
import com.duckduckgram.duckerer.ink.InkGrain;
import com.duckduckgram.duckerer.ink.InkPreviewCell;
import com.duckduckgram.duckerer.ink.InkSwatchRow;

import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.List;

/**
 * mvs Ink Studio: экран, где клиент подстраивается под владельца.
 *
 * <p>Сверху живой образец: строка списка чатов, два пузыря и поле ввода, нарисованные
 * теми же цветами, что и само приложение. Ниже — выбор чернил (акцентного цвета),
 * зерно бумаги и форма аватарок. Всё применяется сразу, без перезапуска.
 *
 * <p>Новых хранилищ экран не заводит: чернила и зерно лежат в {@link DdgConfig},
 * форма аватарок — та же настройка, что слайдер «Закругление аватарок» во внешнем
 * виде, поэтому две копии одной настройки не разъезжаются.
 */
public class DdgInkStudioActivity extends DdgCategoryActivity {

    private static final int TYPE_PREVIEW = 960;
    private static final int TYPE_SWATCHES = 961;

    /** Значения закругления аватарок для вариантов «Круг», «Ink», «Квадрат». */
    private static final int SHAPE_CIRCLE = AppearanceConfig.AVATAR_CORNERS_MAX;
    private static final int SHAPE_INK = 17;
    private static final int SHAPE_SQUARE = 0;

    private InkPreviewCell previewCell;
    private InkSwatchRow swatchRow;

    @Override
    protected int titleRes() {
        return R.string.InkStudioTitle;
    }

    @Override
    protected int searchGuid() {
        return 31500;
    }

    @Override
    protected int searchIcon() {
        return R.drawable.baseline_palette_24;
    }

    @Override
    protected int searchPrefixRes() {
        return R.string.InkStudioTitle;
    }

    @Override
    public boolean onFragmentCreate() {
        DdgConfig.loadConfig(false);
        return super.onFragmentCreate();
    }

    @Override
    protected List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();

        rows.add(Row.custom(TYPE_PREVIEW));

        rows.add(Row.custom(TYPE_SWATCHES));
        rows.add(Row.info(R.string.InkStudioInkInfo));

        rows.add(Row.header(R.string.InkStudioPaperHeader));
        rows.add(Row.radio(R.string.InkStudioGrain, DdgConfig.inkGrain,
                new int[]{R.string.InkGrainOff, R.string.InkGrainFine, R.string.InkGrainVisible}));
        rows.add(Row.info(R.string.InkStudioPaperInfo));

        rows.add(Row.header(R.string.InkStudioShapeHeader));
        rows.add(shapeRow());
        rows.add(Row.info(R.string.InkStudioShapeInfo));

        rows.add(Row.action(R.string.InkStudioReset, R.drawable.msg_reset, this::resetAll));
        rows.add(Row.info(R.string.InkStudioResetInfo));

        return rows;
    }

    /**
     * Строка формы аватарок. Если слайдер во «Внешнем виде» поставил промежуточное
     * значение, добавляется четвёртый вариант «Своя»: иначе ячейка показала бы
     * «Круг» при аватарках, которые кругами не являются.
     */
    private Row shapeRow() {
        final int current = AppearanceConfig.avatarCorners();
        if (current == SHAPE_CIRCLE || current == SHAPE_INK || current == SHAPE_SQUARE) {
            return Row.radioValues(R.string.InkStudioAvatarShape, AppearanceConfig.avatarCorners,
                    new int[]{R.string.InkShapeCircle, R.string.InkShapeInk, R.string.InkShapeSquare},
                    new int[]{SHAPE_CIRCLE, SHAPE_INK, SHAPE_SQUARE});
        }
        return Row.radioValues(R.string.InkStudioAvatarShape, AppearanceConfig.avatarCorners,
                new int[]{R.string.InkShapeCircle, R.string.InkShapeInk, R.string.InkShapeSquare,
                        R.string.InkShapeCustom},
                new int[]{SHAPE_CIRCLE, SHAPE_INK, SHAPE_SQUARE, current});
    }

    /** Вернуть чернила, зерно и форму аватарок к тому, что задумано дизайн-системой. */
    private void resetAll() {
        DdgConfig.inkGrain.setConfigInt(InkGrain.FINE);
        AppearanceConfig.avatarCorners.setConfigInt(SHAPE_CIRCLE);
        InkGrain.refresh();
        // Перекрашивает приложение, только если акцент действительно менялся.
        InkAccent.select(InkAccent.TERRACOTTA);
        refreshCells();
        rebuildRows();
    }

    private void refreshCells() {
        if (previewCell != null) {
            previewCell.refresh();
        }
        if (swatchRow != null) {
            swatchRow.refresh();
        }
    }

    @Override
    protected void onRadioChanged(Row row, int value) {
        if (row.config == DdgConfig.inkGrain) {
            InkGrain.refresh();
        }
        refreshCells();
        rebuildRows();
    }

    @Override
    protected View onCreateCustomViewHolder(ViewGroup parent, int viewType) {
        if (viewType == TYPE_PREVIEW) {
            previewCell = new InkPreviewCell(parent.getContext());
            return previewCell;
        }
        if (viewType == TYPE_SWATCHES) {
            swatchRow = new InkSwatchRow(parent.getContext());
            swatchRow.setListener(index -> {
                if (previewCell != null) {
                    previewCell.refresh();
                }
                rebuildRows();
            });
            return swatchRow;
        }
        return super.onCreateCustomViewHolder(parent, viewType);
    }

    @Override
    protected void onBindCustomViewHolder(RecyclerView.ViewHolder holder, int position) {
        if (holder.itemView == previewCell) {
            previewCell.refresh();
        } else if (holder.itemView == swatchRow) {
            swatchRow.refresh();
        }
    }
}
